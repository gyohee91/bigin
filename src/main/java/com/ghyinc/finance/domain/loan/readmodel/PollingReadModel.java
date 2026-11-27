package com.ghyinc.finance.domain.loan.readmodel;

/**
 * 폴링 서비스가 읽기모델에 요구하는 것만 모은 포트. 구현(Redis)은 서비스가 알 필요 없다.
 */
public interface PollingReadModel {
    boolean isEnabled();

    PollingView read(String inquiryNo, Long clientVersion, int page, int size);

    /** DB 스냅샷 적재. 성공하면 true */
    boolean seed(InquirySnapShot snapShot);
}
