package com.ghyinc.finance.domain.external.nice.dto;

import lombok.Builder;

@Builder
public record AutoInfo(
        String seq,
        String formKind,
        String seatingCapacity,
        String resUseType,
        String resCarModelType
        //기타 등록원부 (갑)정보
) {}
