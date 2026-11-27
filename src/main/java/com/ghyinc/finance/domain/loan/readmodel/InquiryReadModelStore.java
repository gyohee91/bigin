package com.ghyinc.finance.domain.loan.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.PollingMetrics.Outcome;
import com.ghyinc.finance.domain.loan.readmodel.PollingMetrics.RedisOp;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class InquiryReadModelStore {

    public enum Type { OK, SAME, MISS, DOWN }
    public record ReadResult(
            Type type, long version, int total, int success, String status, long card, List<String> itemsJson, long deadlineAtMs
    ) {
        public ReadResult(Type type, long version, int total, int success, String status, long card, List<String> itemsJson) {
            this(type, version, total, success, status, card, itemsJson, 0L);
        }

        static ReadResult of(Type t) {
            return new ReadResult(t, 0, 0, 0, null, 0, List.of(), 0L);
        }
    }

    private record WriteCommand(
            boolean seed, long ttlSec, long nowMs, int total, String status, long deadlineAtMs, List<WriteItem> items
    ) {
        record WriteItem(String member, long score, int stage, String json) {}

        Object[] toArgs() {
            List<String> args = new ArrayList<>(WRITE_HEADER_SIZE + items.size() * WRITE_ITEM_SIZE);
            args.add(seed ? MODE_SEED : MODE_DELTA);    // ARGS[1] mode
            args.add(String.valueOf(ttlSec));           // ARGS[2] ttl
            args.add(String.valueOf(nowMs));            // ARGS[3] now
            args.add(String.valueOf(total));            // ARGS[4] total
            args.add(status == null ? "" : status);     // ARGS[5] status
            args.add(String.valueOf(deadlineAtMs));     // ARGS[6] deadline
            for (WriteItem item : items) {              // ARGS[7..] (member, score, stage, json)
                args.add(item.member());
                args.add(String.valueOf(item.score()));
                args.add(String.valueOf(item.stage()));
                args.add(item.json());
            }
            return args.toArray();
        }
    }

    private volatile long downUntil = 0L;

    /**
     * 쓰기(seed/delta 공용). KEYS: meta, rank, items, stg
     * ARGV: mode(S|D), ttlSec, nowMs, total(-1=유지), status(''=유지), [member, score, stage, json]*
     * <p>
     *  - 단계(stage)가 낮아지는 쓰기는 무시 → 순서 역전/재구성(rebuild)과 delta 경합에도 안전(단조 병합)
     *  - delta(D)는 meta가 없으면 아무것도 만들지 않는다(부분 스냅샷 금지) → -1 반환
     *  - ver: 신규 생성 시 epoch ms(재구성 후 버전 충돌 방지), 이후 변경이 있을 때만 +1
     *  - success는 stg의 stage==2 개수로 매번 재계산(멱등)
     */
    private static final String WRITE_LUA =
                        """
                        -- [WRITE] 읽기모델 반영 (seed / delta)
                        -- KEYS[1] poll:{no}:meta  HASH  ver, total, success, status, term, syncedAt
                        -- KEYS[2] poll:{no}:rank  ZSET  member=loReqtNo, score=금액(null 은 -1)
                        -- KEYS[3] poll:{no}:items HASH  loReqtNo -> 항목 DTO JSON
                        -- KEYS[4] poll:{no}:stg   HASH  loReqtNo -> stage (0=PENDING, 1=SEND_*, 2=SUCCESS)
                        --
                        -- ARGV[1] mode     'S'=seed(DB 스냅샷 적재), 'D'=delta(이벤트 반영, meta 없으면 -1 반환)
                        -- ARGV[2] ttl      초
                        -- ARGV[3] now      epoch ms (최초 생성 시 ver 의 기준, seed 시 syncedAt)
                        -- ARGV[4] total    전체 상품 수 (음수면 total 갱신 안 함)
                        -- ARGV[5] status   조회 상태 ('' 이면 갱신 안 함)
                        -- ARGV[6] deadline FE 대기 마감 epoch ms (0 이면 설정 안 함, 이미 있으면 유지)
                        -- ARGV[7..]        항목 반복 (4개씩): member, score, stage, json
                        --
                        -- 반환: 현재 ver (delta 인데 meta 가 없으면 -1)
                        -- 주의: 큰 수는 string.format('%.0f') 로 출력할 것 (일부 Windows Redis 빌드는 %d 가 32bit)
                        local mode   = ARGV[1]
                        local ttl    = tonumber(ARGV[2])
                        local now    = tonumber(ARGV[3])
                        local total  = tonumber(ARGV[4])
                        local status = ARGV[5]
                        local deadline = tonumber(ARGV[6]) or 0
            
                        local existed = redis.call('EXISTS', KEYS[1]) == 1
                        if (not existed) and mode == 'D' then return -1 end
                        local changed = not existed
                        
                        if deadline > 0 then redis.call('HSETNX', KEYS[1], 'deadline', string.format('%.0f', deadline)) end
            
                        for i = 7, #ARGV, 4 do
                          local member, score, stage, json = ARGV[i], ARGV[i+1], tonumber(ARGV[i+2]), ARGV[i+3]
                          local oldStage = tonumber(redis.call('HGET', KEYS[4], member)) or -1
                          local apply = false
                          if stage > oldStage then
                            apply = true
                          elseif stage == oldStage and redis.call('HGET', KEYS[3], member) ~= json then
                            apply = true
                          end
                          if apply then
                            redis.call('ZADD', KEYS[2], score, member)
                            redis.call('HSET', KEYS[3], member, json)
                            redis.call('HSET', KEYS[4], member, stage)
                            changed = true
                          end
                        end
            
                        local success = 0
                        for _, v in ipairs(redis.call('HVALS', KEYS[4])) do
                          if v == '2' then success = success + 1 end
                        end
            
                        local function setIfDiff(field, value)
                          if redis.call('HGET', KEYS[1], field) ~= value then
                            redis.call('HSET', KEYS[1], field, value)
                            return true
                          end
                          return false
                        end
            
                        if total >= 0 and setIfDiff('total', string.format('%.0f', total)) then changed = true end
                        if setIfDiff('success', string.format('%.0f', success)) then changed = true end
                        if status ~= '' and setIfDiff('status', status) then changed = true end
            
                        local t  = tonumber(redis.call('HGET', KEYS[1], 'total')) or 0
                        local st = redis.call('HGET', KEYS[1], 'status')
                        local term = ((t > 0 and success >= t) or st == 'FAILED') and '1' or '0'
                        redis.call('HSET', KEYS[1], 'term', term)
                        if mode == 'S' then redis.call('HSET', KEYS[1], 'syncedAt', string.format('%.0f', now)) end
            
                        local ver = tonumber(redis.call('HGET', KEYS[1], 'ver')) or 0
                        if not existed then
                          ver = math.max(ver + 1, now)
                          redis.call('HSET', KEYS[1], 'ver', string.format('%.0f', ver))
                        elseif changed then
                          ver = redis.call('HINCRBY', KEYS[1], 'ver', 1)
                        end
            
                        for i = 1, 4 do redis.call('EXPIRE', KEYS[i], ttl) end
                        return ver
                        """;

    /**
     * 읽기. KEYS: meta, rank, items
     * ARGV: clientVer(''=없음), offset, limit, nowMs, reconcileMs
     * 반환: {'MISS'} | {'STALE'} | {'SAME', ver, status, deadline} | {'OK', ver, total, success, status, card, deadline, json...}
     */
    private static final String READ_LUA =
                        """
                        -- [READ] 폴링 조회
                        -- KEYS[1] meta, KEYS[2] rank, KEYS[3] items  (WRITE_LUA 의 KEYS[1..3] 과 동일)
                        --
                        -- ARGV[1] clientVer  클라이언트가 가진 ver ('' 이면 최초 조회)
                        -- ARGV[2] offset     ZREVRANGE 시작 위치
                        -- ARGV[3] limit      페이지 크기
                        -- ARGV[4] now        epoch ms
                        -- ARGV[5] reconcile  ms. 진행 중(term!=1)인데 now-syncedAt 이 이보다 크면 STALE
                        --
                        -- 반환(List):
                        --   {'MISS'}                          meta 없음
                        --   {'STALE'}                         진행 중이고 오래 동기화되지 않음 -> DB 재구성 필요
                        --   {'SAME', ver, status, deadline}   clientVer 와 동일 -> 본문 생략(종료 판단용 status·deadline 만 포함)
                        --   {'OK', ver, total, success, status, card, deadline, json...}   (Java 의 R_* 상수와 같은 순서)
                        local m = redis.call('HMGET', KEYS[1], 'ver', 'total', 'success', 'status', 'term', 'syncedAt', 'deadline')
                        if not m[1] then return {'MISS'} end
            
                        if m[5] ~= '1' and (tonumber(ARGV[4]) - tonumber(m[6] or 0)) > tonumber(ARGV[5]) then
                          return {'STALE'}
                        end
                        if ARGV[1] == m[1] then return {'SAME', m[1], m[4] or '', m[7] or '0'} end
            
                        local offset = tonumber(ARGV[2])
                        local limit  = tonumber(ARGV[3])
                        local res = {'OK', m[1], m[2] or '0', m[3] or '0', m[4] or '', tostring(redis.call('ZCARD', KEYS[2])), m[7] or '0'}
                        local members = redis.call('ZREVRANGE', KEYS[2], offset, offset + limit - 1)
                        for _, mem in ipairs(members) do
                          local j = redis.call('HGET', KEYS[3], mem)
                          if j then res[#res + 1] = j end
                        end
                        return res
                        """;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PollingMetrics metrics;
    private final boolean enabled;
    private final long ttlSec;
    private final long reconcileMs;
    private final long downBackoffMs;

    // ── WRITE_LUA 인자 규약 ─────────────────────────────────────────
    // ARGV[1..6] 헤더: mode, ttl, now, total, status, deadline
    // ARGV[6..]  항목: (member, score, stage, json) x N
    private static final String MODE_SEED = "S";
    private static final String MODE_DELTA = "D";
    private static final int WRITE_HEADER_SIZE = 6;
    private static final int WRITE_ITEM_SIZE = 4;
    private static final long NULL_AMOUNT_SCORE = -1L;  // 금액 null -> 맨 뒤

    // ── READ_LUA 응답 규약 : {code, ver, total, success, status, card, json...} ──
    private static final int R_CODE = 0;
    private static final int R_VER = 1;
    private static final int R_TOTAL = 2;
    private static final int R_SUCCESS = 3;
    private static final int R_STATUS = 4;
    private static final int R_CARD = 5;
    private static final int R_DEADLINE = 6;
    private static final int R_ITEM_FROM = 7;   // 이 인덱스부터 항목 JSON
    private static final int S_STATUS = 2;     // SAME 응답: {code, ver, status, deadline}
    private static final int S_DEADLINE = 3;

    private final RedisScript<Long> writeScript = new DefaultRedisScript<>(WRITE_LUA, Long.class);
    private final RedisScript<List> readScript = new DefaultRedisScript<>(READ_LUA, List.class);

    public InquiryReadModelStore(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            PollingMetrics metrics,
            @Value("${polling.read-model.enabled:false}") boolean enabled,
            @Value("${polling.read-model.ttl-seconds:180}") long ttlSec,
            @Value("${polling.read-model.reconcile-seconds:15}") long reconcileMs,
            @Value("${polling.read-model.down-backoff-ms:3000}") long downBackoffMs) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.enabled = enabled;
        this.ttlSec = ttlSec;
        this.reconcileMs = reconcileMs * 1000L;
        this.downBackoffMs = downBackoffMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static List<String> keys(String inquiryNo) {
        String key = "poll:{" + inquiryNo + "}:";
        return List.of(key + "meta", key + "rank", key + "items", key + "stg");
    }

    private boolean isDown() {
        return System.currentTimeMillis() < downUntil;
    }

    private void markDown(Exception e) {
        downUntil = System.currentTimeMillis() + downBackoffMs;
        log.warn("[ReadModel] Redis 오류 -> {}ms 동안 DB fallback. cause={}", downBackoffMs, e.toString());
    }

    /**
     * 쓰기 가능 여부. 비활성이면 조용히 false, Redis down 구간이면 건너뛴 건수를 센다
     * (건너띈 변경은 reconcile 로 복구되므로, 얼마나 쌓이는지 보기 위한 지표).
     */
    private boolean writable() {
        if (!enabled)   return false;
        if (this.isDown()) {
            metrics.count(Outcome.WRITE_SKIPPED_DOWN);
            return false;
        }
        return true;
    }

    private WriteCommand toCommand(
            boolean seed, int total, String status, List<LoanLimitProductResultDto> items, long deadlineAtMs
    ) throws JsonProcessingException {
        List<WriteCommand.WriteItem> writeItems = new ArrayList<>(items.size());
        for (LoanLimitProductResultDto dto : items) {
            long score = dto.amount() == null ? NULL_AMOUNT_SCORE : dto.amount();
            writeItems.add(new WriteCommand.WriteItem(
                    dto.loReqtNo(), score, stage(dto.status()), objectMapper.writeValueAsString(dto)
            ));
        }
        return new WriteCommand(seed, ttlSec, System.currentTimeMillis(), total, status, deadlineAtMs, writeItems);
    }

    /**
     * 이벤트(커밋 후) 반영. 실패해도 예외 전파 없음(다음 폴링에서 stale/miss -> DB 재구성으로 복구)
     */
    public void apply(InquiryReadModelEvent event) {
        if (!this.writable()) return;
        this.upsert(event.inquiryNo(), event.seed(), event.total(), event.inquiryStatus(), event.items(), event.deadlineAtEpochMs());

    }

    /**
     * 캐시 미스/stale 시 DB 스내샷을 병합 적재 (write-back)
     */
    public boolean seed(InquirySnapShot snapShot) {
        if (!writable())  return false;
        return this.upsert(snapShot.inquiryNo(), true, snapShot.total(), snapShot.status(), snapShot.items(), snapShot.deadlineAtEpochMs());
    }

    public boolean upsert(String inquiryNo, boolean seed, int total, String status, List<LoanLimitProductResultDto> items, long deadlineAtMs) {
        Object[] args;
        try {
            args = this.toCommand(seed, total, status, items, deadlineAtMs).toArgs();
        } catch (Exception e) {
            metrics.count(Outcome.WRITE_FAIL_SERIALIZE);
            log.warn("[ReadModel] 쓰기 인자 조립 실패(Redis 상태와 무관). inquiryNo={} cause={}", inquiryNo, e.toString());
            return false;
        }

        try {
            Long version;
            Timer.Sample sample = metrics.start();
            boolean ok = false;
            try {
                version = redis.execute(writeScript, keys(inquiryNo), args);
                ok = true;
            } finally {
                metrics.stopRedis(sample, RedisOp.WRITE, ok);
            }

            metrics.count(version != null && version == -1 ? Outcome.WRITE_SKIPPED_NO_META : Outcome.WRITE_OK);
            return true;
        } catch (Exception ex) {
            metrics.count(Outcome.WRITE_FAIL);
            this.markDown(ex);
            return false;
        }
    }

    static int stage(PartnerInquiryStatus status) {
        if (status == null || status == PartnerInquiryStatus.PENDING)   return 0;
        if (status == PartnerInquiryStatus.SUCCESS) return 2;
        return 1;   // SEND_SUCCESS, SEND_FAIL, TIMEOUT ..
    }

    public ReadResult read(String inquiryNo, Long clientVersion, int page, int size) {
        if (this.isDown())  return ReadResult.of(Type.DOWN);
        try {
            List<String> keyList = keys(inquiryNo);

            // REDIS_LUA ARGV 순서: clientVer, offset, limit, now, reconcile
            String clientVer    = clientVersion == null ? "" : String.valueOf(clientVersion);
            String offset       = String.valueOf((long) page * size);
            String limit        = String.valueOf(size);
            String now          = String.valueOf(System.currentTimeMillis());
            String reconcile    = String.valueOf(reconcileMs);

            List<?> redisList;
            Timer.Sample sample = metrics.start();
            boolean ok = false;
            try {
                redisList = redis.execute(readScript, keyList.subList(0, 3),
                        clientVer, offset, limit, now, reconcile);
                ok = true;
            } finally {
                metrics.stopRedis(sample, RedisOp.READ, ok);
            }

            if (redisList == null || redisList.isEmpty()) {
                metrics.count(Outcome.MISS);
                return ReadResult.of(Type.MISS);
            }

            String code = String.valueOf(redisList.get(R_CODE));
            switch (code) {
                case "SAME" -> {
                    metrics.count(Outcome.UNCHANGED);
                    return new ReadResult(Type.SAME, Long.parseLong(String.valueOf(redisList.get(R_VER))),
                            0, 0, String.valueOf(redisList.get(S_STATUS)), 0, List.of(),
                            Long.parseLong(String.valueOf(redisList.get(S_DEADLINE))));
                }
                case "OK" -> {
                    metrics.count(Outcome.HIT);
                    List<String> json = new ArrayList<>(Math.max(0, redisList.size() - R_ITEM_FROM));
                    for (int i = R_ITEM_FROM; i < redisList.size(); i++) {
                        json.add(String.valueOf(redisList.get(i)));
                    }
                    return new ReadResult(
                            Type.OK,
                            Long.parseLong(String.valueOf(redisList.get(R_VER))),
                            Integer.parseInt(String.valueOf(redisList.get(R_TOTAL))),
                            Integer.parseInt(String.valueOf(redisList.get(R_SUCCESS))),
                            String.valueOf(redisList.get(R_STATUS)),
                            Long.parseLong(String.valueOf(redisList.get(R_CARD))),
                            json,
                            Long.parseLong(String.valueOf(redisList.get(R_DEADLINE)))
                    );
                }
                default -> {    // MISS / STALE
                    metrics.count("STALE".equals(code) ? Outcome.STALE : Outcome.MISS);
                    return ReadResult.of(Type.MISS);
                }
            }
        } catch (Exception e) {
            metrics.count(Outcome.READ_FAIL);
            this.markDown(e);
            return ReadResult.of(Type.DOWN);
        }
    }
}
