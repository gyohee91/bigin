package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.config.InquiryPollingProperties;
import com.ghyinc.finance.domain.loan.config.StaleInquiryProperties;
import com.ghyinc.finance.domain.loan.dto.InquiryProgress;
import com.ghyinc.finance.domain.loan.dto.LoanLimitPollingResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelStore;
import com.ghyinc.finance.domain.loan.readmodel.InquirySnapShot;
import com.ghyinc.finance.domain.loan.readmodel.InquirySnapShotLoader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FE 폴링 전용 진입점. 이 클래스는 @Transactional이 아니다(Redis 히트 시 DB 커넥션을 잡지 않기 위해)
 * DB가 필요한 구간(스냅샷 재구성, fallback)만 각각 짧은 읽기 트랜잭션 빈을 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitPollingService {
    private final InquiryReadModelStore store;
    private final LoanLimitService loanLimitService;
    private final InquirySnapShotLoader snapShotLoader;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final StaleInquiryFailService staleInquiryFailService;
    private final InquiryPollingProperties pollingProperties;
    private final StaleInquiryProperties staleInquiryProperties;

    private static final InquiryReadModelStore.ReadResult MISS_RESULT = new InquiryReadModelStore.ReadResult(InquiryReadModelStore.Type.MISS, 0, 0, 0, null, 0, List.of());

    private enum PollPath {
        DB, REDIS, UNCHANGED, REBUILD, FALLBACK_DOWN, FALLBACK_SEED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record Polled(LoanLimitPollingResponse response, PollPath path) {}

    /**
     * 읽기 결과 + 파싱된 항목. 파싱 실패는 MISS로 강등된 값으로 표현한다.
     * @param readResult
     * @param items
     */
    private record Parsed(InquiryReadModelStore.ReadResult readResult, List<LoanLimitProductResultDto> items) {
        static Parsed miss() {
            return new Parsed(MISS_RESULT, List.of());
        }
    }

    public LoanLimitPollingResponse getInquiryResult(String inquiryNo, int page, int size, Long version) {
        Pageable pageable = PageRequest.of(page, size); // 입력 검증(page>=0, size>=1)을 더한다

        Timer.Sample sample = Timer.start(meterRegistry);
        PollPath path = PollPath.DB;   // 플래그 OFF(AS-IS) 경로

        try {
            if (!store.isEnabled()) {
                return loanLimitService.getInquiryResult(inquiryNo, pageable);
            }

            path = PollPath.REDIS;
            Polled polled = this.pollFromReadModel(inquiryNo, pageable, version);
            path = polled.path();

            return polled.response();
        } finally {
            sample.stop(this.timer("polling.service", path.tag()));
        }
    }

    private Polled pollFromReadModel(String inquiryNo, Pageable pageable, Long version) {
        int page = pageable.getPageNumber();
        int size = pageable.getPageSize();

        InquiryReadModelStore.ReadResult firstReadResult = store.read(inquiryNo, version, page, size);

        // Redis 장애 -> 기존 DB 경로
        if (firstReadResult.type() == InquiryReadModelStore.Type.DOWN) {
            store.count("fallback");
            return new Polled(loanLimitService.getInquiryResult(inquiryNo, pageable), PollPath.FALLBACK_DOWN);
        }

        Parsed parsed = this.parse(inquiryNo, firstReadResult);
        PollPath path = PollPath.REDIS;

        // MISS/STALE/파싱 실패 -> DB 재구성 + 병합 적재
        if (parsed.readResult().type() == InquiryReadModelStore.Type.MISS) {
            path = PollPath.REBUILD;
            InquirySnapShot snapShot = snapShotLoader.load(inquiryNo);

            // 적재본을 다시 읽어 version과 내용을 일치시킨다.
            parsed = store.seed(snapShot)
                    ? this.parse(inquiryNo, store.read(inquiryNo, version, page, size))
                    : Parsed.miss();

            InquiryReadModelStore.Type type = parsed.readResult().type();
            if (type == InquiryReadModelStore.Type.MISS || type == InquiryReadModelStore.Type.DOWN) {
                store.count("fallback");
                LoanLimitPollingResponse response = this.decorate(
                        this.fromSnapShot(snapShot, page, size),
                        snapShot.status(), snapShot.deadlineAtEpochMs()
                );
                return new Polled(response, PollPath.FALLBACK_SEED);
            }
        }

        parsed = this.repairIfStale(inquiryNo, parsed, version, page, size);
        InquiryReadModelStore.ReadResult readResult = parsed.readResult();

        if (readResult.type() == InquiryReadModelStore.Type.SAME) {
            PollPath samePath = path == PollPath.REBUILD ? PollPath.REBUILD : PollPath.UNCHANGED;
            LoanLimitPollingResponse response = this.decorate(
                    LoanLimitPollingResponse.unchanged(inquiryNo, readResult.version()),
                    readResult.status(),
                    readResult.deadlineAtMs()
            );
            return new Polled(response, samePath);
        }

        return new Polled(this.decorate(
                this.toResponse(inquiryNo, parsed, page, size),
                readResult.status(),
                readResult.deadlineAtMs()
        ), path);
    }

    private LoanLimitPollingResponse toResponse(String inquiryNo, Parsed parsed, int page, int size) {
        InquiryReadModelStore.ReadResult readResult = parsed.readResult();
        return LoanLimitPollingResponse.of(
                inquiryNo,
                readResult.status(),
                new InquiryProgress(readResult.total(), readResult.success()),
                parsed.items(),
                readResult.card(),
                page,
                size
        ).toBuilder()
                .version(readResult.version())
                .changed(true)
                .build();
    }

    private Parsed parse(String inquiryNo, InquiryReadModelStore.ReadResult readResult) {
        if(readResult.type() != InquiryReadModelStore.Type.OK)
            return new Parsed(readResult, List.of());
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<LoanLimitProductResultDto> items = new ArrayList<>(readResult.itemsJson().size());
            for(String json : readResult.itemsJson()) {
                items.add(objectMapper.readValue(json, LoanLimitProductResultDto.class));
            }
            return new Parsed(readResult, items);
        } catch (JsonProcessingException e) {
            store.count("parse_fail");
            log.warn("[ReadModel] 항목 역직렬화 실패 -> MISS 로 처리. inquiryNo={} cause={}", inquiryNo, e.toString());
            return Parsed.miss();
        } finally {
            sample.stop(this.timer("polling.readmodel.parse", null));
        }
    }

    /**
     * Redis 적재까지 실패했을 때 DB 스냅샷으로 직접 응답 (version 없음 -> FE는 항상 새 데이터로 처리)
     */
    private LoanLimitPollingResponse fromSnapShot(InquirySnapShot snapShot, int page, int size) {
        List<LoanLimitProductResultDto> all = snapShot.items();
        int from = (int) Math.min((long) page * size, all.size());
        int to = (int) Math.min((long)  from + size, all.size());
        int success = (int) all.stream().filter(d -> d.status() == PartnerInquiryStatus.SUCCESS).count();
        return LoanLimitPollingResponse.of(
                snapShot.inquiryNo(),
                snapShot.status(),
                new InquiryProgress(snapShot.total(), success),
                new ArrayList<>(all.subList(from, to)),
                all.size(),
                page,
                size
        );
    }

    private LoanLimitPollingResponse decorate(LoanLimitPollingResponse response, String status, long deadlineAtMs) {
        return response.withDeadline(InquiryStatus.parseOrNull(status), deadlineAtMs,
                pollingProperties.pollInterval().toMillis());
    }

    private Parsed repairIfStale(
            String inquiryNo, Parsed currentReadResult, Long version, int page, int size
    ) {
        InquiryReadModelStore.ReadResult readResult = currentReadResult.readResult();
        InquiryStatus status = InquiryStatus.parseOrNull(readResult.status());
        if (readResult.deadlineAtMs() <= 0 || (status != null && status.isTerminal()))  return currentReadResult;

        long createdAtMs = readResult.deadlineAtMs() - pollingProperties.clientWaitLimit().toMillis();
        if (System.currentTimeMillis() < createdAtMs + staleInquiryProperties.minTimeout().toMillis())  return currentReadResult;  // 아직 stale 불가

        try {
            if (!staleInquiryFailService.repairIfStale(inquiryNo))  return currentReadResult;
        } catch (Exception e) {
            log.warn("[read-repair] 실패 inquiryNo={} cause={}", inquiryNo, e.toString());
            return currentReadResult;
        }

        store.count("read_repair");
        Parsed again = this.parse(inquiryNo, store.read(inquiryNo, version, page, size));
        InquiryReadModelStore.Type type = again.readResult().type();
        return (type == InquiryReadModelStore.Type.OK
                || type == InquiryReadModelStore.Type.SAME) ? again : currentReadResult;
    }

    private Timer timer(String name, String path) {
        Timer.Builder b = Timer.builder(name).publishPercentiles(0.5, 0.95, 0.99);
        if (path != null) b.tag("path", path);
        return b.register(meterRegistry);
    }
}
