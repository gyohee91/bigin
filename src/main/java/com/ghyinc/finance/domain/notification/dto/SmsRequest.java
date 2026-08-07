package com.ghyinc.finance.domain.notification.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsRequest {
    private String phoneNumber;
    private String title;
    private String contents;
}
