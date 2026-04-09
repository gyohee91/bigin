package com.ghyinc.finance.domain.external.nice.service;

import com.ghyinc.finance.domain.external.nice.dto.AutoInfo;
import com.ghyinc.finance.domain.external.nice.dto.AutoSecondInfo;
import com.ghyinc.finance.domain.external.nice.dto.NiceDnrResult;
import org.springframework.stereotype.Service;

@Service
public class NiceDnrService {

    /**
     * Nice DNR 조회
     *
     * @param carNo 차량번호
     * @param name  차주
     * @return      자동차등록원부 결과
     */
    public NiceDnrResult inquireNiceDnr(String carNo, String name) {
        // 로컬 환경 테스트를 위해 가 데이터 set
        AutoInfo autoInfo = AutoInfo.builder()
                .seq("1")
                .formKind("갑")
                .resUseType("영업용")
                .seatingCapacity("5")
                .resCarModelType("승합대형")
                .build();   //갑 정보 가져옴

        AutoSecondInfo autoSecondInfo = AutoSecondInfo.builder().build();   //을 정보 가져옴

        //NICE와 통신하여 DNR 결과 조회 후 리턴
        return NiceDnrResult.builder()
                .autoInfo(autoInfo)
                .autoSecondInfo(autoSecondInfo)
                .build();
    }
}
