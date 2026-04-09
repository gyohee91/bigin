package com.ghyinc.finance.domain.external.nice.dto;

import lombok.Builder;

@Builder
public record AutoSecondInfo(
        String seq,
        String formKind,
        String resEulNo
        //기타 등록원부 (을)정보
) {}
