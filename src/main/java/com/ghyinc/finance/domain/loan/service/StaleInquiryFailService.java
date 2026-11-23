package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelEvent;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StaleInquiryFailService {
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public boolean failIfStale(Long id, String inquiryNo, InquiryStatus expected, LocalDateTime before) {
        int updated = loanLimitInquiryRepository.failIfStale(id, expected, before, LocalDateTime.now());
        if (updated == 0) {
            return false;       // 그 사이 정상 진행되었거나 다른 인스턴스가 처리함
        }

        // 폴링 읽기모델(Redis) 외에도 FAILED 반영. AFTER_COMMIT 리스너가 처리한다 (markFailed와 동일 방식)
        applicationEventPublisher.publishEvent(InquiryReadModelEvent.delta(inquiryNo, InquiryStatus.FAILED.name(), List.of()));
        return true;
    }
}
