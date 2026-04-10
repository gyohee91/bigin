package com.ghyinc.finance.domain.external.nice.dto;

import lombok.Builder;

/**
 * 자동차등록원부(갑)
 * @param seq       갑/을부 행번호
 * @param formKind  갑/을부 구분
 * @param resCarNo  자동차등록번호
 * @param seatingCapacity   수용인원
 * @param resMotorType      원동기형식
 * @param resUseType        용도
 * @param resCarModelType   차종
 */
@Builder
public record AutoInfo(
        String seq,
        String formKind,
        String resCarNo,
        String seatingCapacity,
        String resMotorType,
        String resUseType,
        String resCarModelType
        //기타 등록원부 (갑)정보
) {}
