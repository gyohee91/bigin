package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.config.InquiryPollingProperties;
import com.ghyinc.finance.domain.loan.config.StaleInquiryProperties;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 폴링 중 발견한 stale 진행 건(타임아웃을 넘겼는데 아직 진행 중)을 FAILED로 보정(read-repair)한다.
 * 보정 여부 판단(시간 기준)과 실제 보정(CAS)만 담당하고, 읽기모델 재조회는 호출자가 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleInquiryRepair {
    private final StaleInquiryFailService staleInquiryFailService;
    private final InquiryPollingProperties pollingProperties;
    private final StaleInquiryProperties staleInquiryProperties;

    /**
     *
     * @param status    알 수 없으면 null (종료 상태로 보지 않는다)
     * @return  실제로 상태를 바꿨으면 true. 대상이 아니거나 실패하면 false(폴링 응답은 정상으로 나가야 하므로 예외를 던지지 않는다)
     */
    public boolean repairIfDue(String inquiryNo, InquiryStatus status, long deadlineAtMs) {
        if (deadlineAtMs <= 0 || (status != null && status.isTerminal()))   return false;

        long createdAtMs = deadlineAtMs - pollingProperties.clientWaitLimit().toMillis();
        if (System.currentTimeMillis() < createdAtMs + staleInquiryProperties.minTimeout().toMillis()) {
            return false;   // 아직 stale이 될 수 없다
        }

        try {
            return staleInquiryFailService.repairIfStale(inquiryNo);
        } catch (Exception e) {
            log.warn("[read-repair] 실패 inquiryNo={} cause={}", inquiryNo, e.toString());
            return false;
        }
    }
}
