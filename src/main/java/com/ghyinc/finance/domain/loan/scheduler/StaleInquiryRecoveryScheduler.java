package com.ghyinc.finance.domain.loan.scheduler;

import com.ghyinc.finance.domain.loan.dto.StaleInquiryView;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.service.StaleInquiryFailService;
import com.ghyinc.finance.global.config.StaleInquiryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StaleInquiryRecoveryScheduler {
    private final StaleInquiryFailService staleInquiryFailService;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final StaleInquiryProperties properties;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${app.inquiry-recovery.fixed-delay:PT10S}")
    @SchedulerLock(
            name = "loanLimitInquiryRecoveryScheduler_failStaleInquiries",
            lockAtLeastFor = "20s",
            lockAtMostFor = "5m"
    )
    public void failStaleInquiries() {
        long deadlineNanos = System.nanoTime() + properties.maxRunDuration().toNanos();
        LocalDateTime now = LocalDateTime.now();
        process(InquiryStatus.ACCEPTED, now.minus(properties.acceptedTimeout()), deadlineNanos);
        process(InquiryStatus.PENDING, now.minus(properties.pendingTimeout()), deadlineNanos);
        process(InquiryStatus.IN_PROGRESS, now.minus(properties.inProgressTimeout()), deadlineNanos);
    }

    private void process(InquiryStatus status, LocalDateTime before, long deadlineNanos) {
        int total = 0;
        while (System.nanoTime() < deadlineNanos) {
            // FAILED가 된 건은 조회 대상에서 빠지므로 항상 첫 페이지만 다시 읽으면 된다
            List<StaleInquiryView> staleInquiryViews =
                    loanLimitInquiryRepository.findStale(status, before, PageRequest.of(0, properties.batchSize()));
            List<Long> ids = staleInquiryViews.stream().map(StaleInquiryView::getId).toList();
            if (ids.isEmpty())  break;

            int failed = 0;
            try {
                failed = staleInquiryFailService.failAllIfStale(ids, status, before);
            } catch (Exception e) {
                log.error("[한도조회 복구] 묶음 처리 실패, 건별 처리로 전환. status = {}, size={}", status, ids.size(), e);
                meterRegistry.counter("inquiry.recovery.error", "from", status.name()).increment();
                failed = failOneByOne(staleInquiryViews, status, before);
            }
            total += failed;
            if (failed == 0 || ids.size() < properties.batchSize()) break;  // 진전 없으면 중단 (무한 루프 방지)
        }
        if (total > 0) {
            log.warn("[한도조회 복구] {} 상태에서 멈춘 {}건을 FAILED 처리", status, total);
            meterRegistry.counter("inquiry.recovery.failed", "from", status.name()).increment(total);
        }
    }

    private int failOneByOne(List<StaleInquiryView> staleInquiryViews, InquiryStatus status, LocalDateTime before) {
        int failed = 0;
        for(StaleInquiryView view : staleInquiryViews) {
            try {
                if (staleInquiryFailService.failIfStale(view.getId(), view.getInquiryNo(), status, before))  failed++;
            } catch (Exception e) {
                log.error("[한도조회 복구] 건별 처리 실패 id={}, status={}", view.getId(), status, e);
                meterRegistry.counter("inquiry.recovery.error", "from", status.name()).increment();
            }
        }
        return failed;
    }
}
