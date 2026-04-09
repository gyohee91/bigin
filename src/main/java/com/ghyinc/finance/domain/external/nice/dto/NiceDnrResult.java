package com.ghyinc.finance.domain.external.nice.dto;

import lombok.Builder;

@Builder
public record NiceDnrResult(
        AutoInfo autoInfo,  //자동자등록원부(갑)
        AutoSecondInfo autoSecondInfo   //자동자등록원부(을)
) {
}
