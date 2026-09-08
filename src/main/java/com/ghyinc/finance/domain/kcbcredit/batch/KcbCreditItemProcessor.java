package com.ghyinc.finance.domain.kcbcredit.batch;

import com.ghyinc.finance.domain.kcbcredit.dto.KcbCreditRecord;
import com.ghyinc.finance.domain.kcbcredit.enums.KcbCreditType;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

@Component
public class KcbCreditItemProcessor implements ItemProcessor<KcbCreditRecord, KcbCreditRecord> {
    @Override
    public KcbCreditRecord process(KcbCreditRecord item) {
        KcbCreditType type = item.resolveKcbCreditType();
        if (type != KcbCreditType.BALANCE_INCREASE && type != KcbCreditType.BALANCE_DECREASE) {
            return null;
        }

        return item;
    }
}
