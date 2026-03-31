package com.ghyinc.finance.domain.loan.external.coocon.service;

import com.ghyinc.finance.domain.loan.external.coocon.dto.KbAppraisalResult;
import com.ghyinc.finance.domain.loan.external.coocon.dto.RespData;
import org.springframework.stereotype.Service;

@Service
public class KbAppraisalService {
    public KbAppraisalResult inquireKbAppraisal(String address) {
        // 로컬 환경 테스트를 위해 가 데이터 set
        RespData respData = RespData.builder()
                .region1Code("11")
                .region1Name("서울시")
                .region2Code("11680")
                .region2Name("깅남구")
                .region3Code("11680108")
                .region3Name("논현동")
                .ldongCode("11680108")
                .build();

        // 쿠콘과 통신하여 KB부동산시세 결과 조회 후 리턴
        return KbAppraisalResult.builder()
                .respData(respData)
                .build();
    }
}
