package com.ghyinc.finance.domain.loan.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import io.micrometer.core.instrument.MeterRegistry;
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
            Type type, long version, int total, int success, String status, long card, List<String> itemsJson
    ) {
        static ReadResult of(Type t) {
            return new ReadResult(t, 0, 0, 0, null, 0, List.of());
        }
    }

    private volatile long downUntil = 0L;

    private static final String WRITE_LUA =
                        """
                        local mode   = ARGV[1]
                        local ttl    = tonumber(ARGV[2])
                        local now    = tonumber(ARGV[3])
                        local total  = tonumber(ARGV[4])
                        local status = ARGV[5]
            
                        local existed = redis.call('EXISTS', KEYS[1]) == 1
                        if (not existed) and mode == 'D' then return -1 end
                        local changed = not existed
            
                        for i = 6, #ARGV, 4 do
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
    private static final String READ_LUA =
                        """
                        local m = redis.call('HMGET', KEYS[1], 'ver', 'total', 'success', 'status', 'term', 'syncedAt')
                        if not m[1] then return {'MISS'} end
            
                        if m[5] ~= '1' and (tonumber(ARGV[4]) - tonumber(m[6] or 0)) > tonumber(ARGV[5]) then
                          return {'STALE'}
                        end
                        if ARGV[1] == m[1] then return {'SAME', m[1]} end
            
                        local offset = tonumber(ARGV[2])
                        local limit  = tonumber(ARGV[3])
                        local res = {'OK', m[1], m[2] or '0', m[3] or '0', m[4] or '', tostring(redis.call('ZCARD', KEYS[2]))}
                        local members = redis.call('ZREVRANGE', KEYS[2], offset, offset + limit - 1)
                        for _, mem in ipairs(members) do
                          local j = redis.call('HGET', KEYS[3], mem)
                          if j then res[#res + 1] = j end
                        end
                        return res
                        """;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final long ttlSec;
    private final long reconcileMs;
    private final long downBackoffMs;

    private final RedisScript<Long> writeScript = new DefaultRedisScript<>(WRITE_LUA, Long.class);
    private final RedisScript<List> readScript = new DefaultRedisScript<>(READ_LUA, List.class);

    public InquiryReadModelStore(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${polling.read-model.enabled:false}") boolean enabled,
            @Value("${polling.read-model.ttl-seconds:3600}") long ttlSec,
            @Value("${polling.read-model.reconcile-seconds:15}") long reconcileMs,
            @Value("${polling.read-model.down-backoff-ms:3000}") long downBackoffMs) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.ttlSec = ttlSec;
        this.reconcileMs = reconcileMs * 1000L;
        this.downBackoffMs = downBackoffMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void count(String result) {
        meterRegistry.counter("polling.readmodel", "result", result).increment();
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
     * 이벤트(커밋 후) 반영. 실패해도 예외 전파 없음(다음 폴링에서 stale/miss -> DB 재구성으로 복구)
     */
    public void apply(InquiryReadModelEvent event) {
        if (!enabled || this.isDown()) return;
        this.upsert(event.inquiryNo(), event.seed(), event.total(), event.inquiryStatus(), event.items());

    }

    /**
     * 캐시 미스/stale 시 DB 스내샷을 병합 적재 (write-back)
     */
    public boolean seed(InquirySnapShot snapShot) {
        if (!enabled || this.isDown())  return false;
        return this.upsert(snapShot.inquiryNo(), true, snapShot.total(), snapShot.status(), snapShot.items());
    }

    public boolean upsert(String inquiryNo, boolean seed, int total, String status, List<LoanLimitProductResultDto> items) {
        try {
            List<Object> args = new ArrayList<>(5 + items.size() * 4);
            args.add(seed ? "S" : "D");
            args.add(String.valueOf(ttlSec));
            args.add(String.valueOf(System.currentTimeMillis()));
            args.add(String.valueOf(total));
            args.add(status == null ? "" : status);
            for(LoanLimitProductResultDto d : items) {
                args.add(d.loReqtNo());
                args.add(String.valueOf(d.amount() == null ? -1L : d.amount()));    // 금액 null -> -1(맨뒤)
                args.add(String.valueOf(stage(d.status())));
                args.add(objectMapper.writeValueAsString(d));
            }
            Long ver = redis.execute(writeScript, keys(inquiryNo), args.toArray());
            this.count(ver != null && ver == -1 ? "write_skipped_no_meta": "write_ok");
            return true;
        } catch (Exception ex) {
            this.count("write_fail");
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
            List<?> redisList = redis.execute(readScript, keyList.subList(0, 3),
                    clientVersion == null ? "" : String.valueOf(clientVersion),
                    String.valueOf(page * size),
                    String.valueOf(size),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(reconcileMs)
            );
            if (redisList == null || redisList.isEmpty()) {
                this.count("miss");
                return ReadResult.of(Type.MISS);
            }

            String code = String.valueOf(redisList.get(0));
            switch (code) {
                case "SAME" -> {
                    this.count("unchanged");
                    return new ReadResult(Type.SAME, Long.parseLong(String.valueOf(redisList.get(1))),
                            0, 0, null, 0, List.of());
                }
                case "OK" -> {
                    this.count("hit");
                    List<String> json = new ArrayList<>();
                    for (int i = 6; i < redisList.size(); i++) {
                        json.add(String.valueOf(redisList.get(i)));
                    }
                    return new ReadResult(
                            Type.OK,
                            Long.parseLong(String.valueOf(redisList.get(1))),
                            Integer.parseInt(String.valueOf(redisList.get(2))),
                            Integer.parseInt(String.valueOf(redisList.get(3))),
                            String.valueOf(redisList.get(4)),
                            Long.parseLong(String.valueOf(redisList.get(5))),
                            json
                    );
                }
                default -> {    // MISS / STALE
                    this.count(code.toLowerCase());
                    return ReadResult.of(Type.MISS);
                }
            }
        } catch (Exception e) {
            this.count("read_fail");
            this.markDown(e);
            return ReadResult.of(Type.DOWN);
        }
    }
}
