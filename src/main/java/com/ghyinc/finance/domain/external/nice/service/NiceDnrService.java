package com.ghyinc.finance.domain.external.nice.service;

import com.ghyinc.finance.domain.external.nice.adaptor.NiceDnrAdaptor;
import com.ghyinc.finance.domain.external.nice.dto.AutoInfo;
import com.ghyinc.finance.domain.external.nice.dto.AutoSecondInfo;
import com.ghyinc.finance.domain.external.nice.dto.NiceDnrRequest;
import com.ghyinc.finance.domain.external.nice.dto.NiceDnrResult;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Nice DNR 조회 서비스 구현체
 */
@Service
@RequiredArgsConstructor
public class NiceDnrService {
    private final NiceDnrAdaptor niceDnrAdaptor;

    /**
     * Nice DNR 조회
     *
     * @param carNo 차량번호
     * @param name  차주
     * @return      자동차등록원부 결과
     */
    public NiceDnrResult inquireNiceDnr(String carNo, String name) {
        NiceDnrRequest request = NiceDnrRequest.builder()
                .ownerNm(name)
                .vhrNo(carNo)
                .build();

        NiceDnrResult response = niceDnrAdaptor.inquire(request);

        if(!"SUCCESS".equals(response.resultCode())) {
            throw new ExternalApiFailException("NICE_DNR_ERROR", "NICE DNR 오류 " + response.resultCode());
        }

        //NICE와 통신하여 DNR 결과 조회 후 리턴
        return this.toResponse(response);
    }

    private NiceDnrResult toResponse(NiceDnrResult response) {
        // 로컬 환경 테스트를 위해 가 데이터 set
        AutoInfo autoInfo = AutoInfo.builder()
                .seq("1")
                .formKind("갑")
                .resCarNo("12가1234")
                .resUseType("영업용")
                .seatingCapacity("5")
                .resMotorType("64FP")
                .resCarModelType("승합대형")
                .build();   //갑 정보 가져옴

        AutoSecondInfo autoSecondInfo = AutoSecondInfo.builder().build();

        return NiceDnrResult.builder()
                .autoInfo(autoInfo)
                .autoSecondInfo(autoSecondInfo)
                .build();
    }
}
