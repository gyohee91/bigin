package com.ghyinc.finance.domain.notification.dto;

import lombok.*;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KakaoRequest {
    private String talkId;
    private String title;
    private String contents;
}
