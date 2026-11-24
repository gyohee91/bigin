package com.ghyinc.finance.domain.loan.dto;

import java.time.LocalDateTime;

public interface StaleInquiryView {
    Long getId();
    String getInquiryNo();
    LocalDateTime getUpdatedAt();
}
