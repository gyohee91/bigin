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
        LocalDateTime now = LocalDateTime.now();
        process(InquiryStatus.ACCEPTED, now.minus(properties.acceptedTimeout()));
        process(InquiryStatus.PENDING, now.minus(properties.pendingTimeout()));
        process(InquiryStatus.IN_PROGRESS, now.minus(properties.inProgressTimeout()));
    }

    private void process(InquiryStatus status, LocalDateTime before) {
        List<StaleInquiryView> targets =
                loanLimitInquiryRepository.findStale(status, before, PageRequest.of(0, properties.batchSize()));
        int failed = 0;
        for(StaleInquiryView target : targets) {
            try {
                if (staleInquiryFailService.failIfStale(target.getId(), target.getInquiryNo(), status, before)) {
                    failed++;
                }
            } catch (Exception e) {
                log.error("[한도조회 복구] 처리 실패 id={}, status = {}", target.getId(), status, e);
            }
        }
        if (failed > 0) {
            log.warn("[한도조회 복구] {} 상태에서 멈춘 {}건을 FAILED 처리", status, failed);
            meterRegistry.counter("inquiry.recovery.failed", "from", status.name()).increment(failed);
        }
    }
}
