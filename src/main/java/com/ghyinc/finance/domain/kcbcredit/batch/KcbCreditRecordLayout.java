package com.ghyinc.finance.domain.kcbcredit.batch;

import org.springframework.batch.item.file.transform.Range;

public final class KcbCreditRecordLayout {
    public static final String[] FIELD_NAME = {
            "ci", "customerName", "kcbCreditTypeCode", "beforeBalance", "afterBalance", "changedAt"
    };

    public static final Range[] FIELD_RANGES = {
            new Range(1, 88),       // CI (88자리)
            new Range(89, 108),     // 고객명 (20자리)
            new Range(109, 110),    // 변동구분코드 (2자리)
            new Range(111, 125),    // 이전잔액 (15자리)
            new Range(126, 140),    // 이후잔액 (15자리)
            new Range(141, 148)     // 변동일자 (8자리)
    };
}
