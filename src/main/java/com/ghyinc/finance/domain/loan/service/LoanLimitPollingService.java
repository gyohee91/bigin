package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.config.InquiryPollingProperties;
import com.ghyinc.finance.domain.loan.dto.InquiryProgress;
import com.ghyinc.finance.domain.loan.dto.LoanLimitPollingResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.*;
import com.ghyinc.finance.domain.loan.readmodel.PollingMetrics.Path;
import com.ghyinc.finance.domain.loan.readmodel.PollingMetrics.Outcome;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * FE 폴링 전용 진입점. 이 클래스는 @Transactional이 아니다(Redis 히트 시 DB 커넥션을 잡지 않기 위해)
 * DB가 필요한 구간(스냅샷 재구성, fallback)만 각각 짧은 읽기 트랜잭션 빈을 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitPollingService {
    private final PollingReadModel readModel;
    private final LoanLimitService loanLimitService;
    private final InquirySnapShotLoader snapShotLoader;
    private final PollingMetrics metrics;

    private final StaleInquiryRepair staleInquiryRepair;
    private final InquiryPollingProperties pollingProperties;

    private record Polled(LoanLimitPollingResponse response, Path path) {}

    public LoanLimitPollingResponse getInquiryResult(String inquiryNo, int page, int size, Long version) {
        Pageable pageable = PageRequest.of(page, size); // 입력 검증(page>=0, size>=1)을 더한다

        Timer.Sample sample = metrics.start();
        Path path = Path.DB;   // 플래그 OFF(AS-IS) 경로

        try {
            if (!readModel.isEnabled()) {
                return loanLimitService.getInquiryResult(inquiryNo, pageable);
            }

            path = Path.REDIS;
            Polled polled = this.pollFromReadModel(inquiryNo, pageable, version);
            path = polled.path();

            return polled.response();
        } finally {
           metrics.stop(sample, path);
        }
    }

    private Polled pollFromReadModel(String inquiryNo, Pageable pageable, Long version) {
        int page = pageable.getPageNumber();
        int size = pageable.getPageSize();

        PollingView firstReadResult = readModel.read(inquiryNo, version, page, size);

        // Redis 장애 -> 기존 DB 경로
        if (firstReadResult instanceof PollingView.Down) {
            metrics.count(Outcome.FALLBACK);
            return new Polled(loanLimitService.getInquiryResult(inquiryNo, pageable), Path.FALLBACK_DOWN);
        }

        Path path = Path.REDIS;
        PollingView.Found found;

        if (firstReadResult instanceof PollingView.Found firstFound) {
            found = firstFound;
        } else {
            // MISS(없음/STALE/파싱 실패) -> DB 재구성 + 병합 적재
            path = Path.REBUILD;
            InquirySnapShot snapShot = snapShotLoader.load(inquiryNo);

            // 적재본을 다시 읽어 version과 내용을 일치시킨다.
            PollingView reload = readModel.seed(snapShot)
                    ? readModel.read(inquiryNo, version, page, size)
                    : PollingView.Miss.INSTANCE;

            if (!(reload instanceof PollingView.Found rebuilt)) {
                metrics.count(Outcome.FALLBACK);
                LoanLimitPollingResponse response = this.decorate(
                        this.fromSnapShot(snapShot, page, size),
                        snapShot.status(), snapShot.deadlineAtEpochMs()
                );
                return new Polled(response, Path.FALLBACK_SEED);
            }
            found = rebuilt;
        }

        found = this.repairIfStale(inquiryNo, found, version, page, size);

        Path resultPath = (found instanceof PollingView.Unchanged && path != Path.REBUILD)
                ? Path.UNCHANGED : path;
        return new Polled(this.toResponse(inquiryNo, found, page, size), resultPath);
    }

    private LoanLimitPollingResponse toResponse(String inquiryNo, PollingView.Found found, int page, int size) {
        LoanLimitPollingResponse response;
        if (found instanceof PollingView.Hit hit) {
            response = LoanLimitPollingResponse.of(
                            inquiryNo,
                            hit.status(),
                            new InquiryProgress(hit.total(), hit.success()),
                            hit.items(),
                            hit.totalElements(),
                            page,
                            size
                    ).toBuilder()
                    .version(hit.version())
                    .changed(true)
                    .build();
        } else {
            response = LoanLimitPollingResponse.unchanged(inquiryNo, found.version());
        }
        return this.decorate(response, found.status(), found.deadlineAtMs());
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

    private PollingView.Found repairIfStale(
            String inquiryNo, PollingView.Found found, Long version, int page, int size
    ) {
        InquiryStatus status = InquiryStatus.parseOrNull(found.status());
        if (!staleInquiryRepair.repairIfDue(inquiryNo, status, found.deadlineAtMs()))  return found;

        metrics.count(Outcome.READ_REPAIR);
        PollingView again = readModel.read(inquiryNo, version, page, size);
        return again instanceof PollingView.Found foundAgain ? foundAgain : found;
    }
}
