package com.ghyinc.finance.domain.loan.external.coocon.service;

import com.ghyinc.finance.domain.loan.external.coocon.dto.KbAppraisalResult;
import org.springframework.stereotype.Service;

@Service
public class KbAppraisalService {
    public KbAppraisalResult inquireKbAppraisal(String address) {
        //쿠콘과 통신하여 KB부동산시세 결과 조회 후 리턴
        return KbAppraisalResult.builder().build();
    }
}
