package com.ghyinc.finance.domain.notification.dto;

import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "알림 발송 등록(요청)")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationSendRequest {
    @Schema(description = "고객Id", example = "1")
    private Long userId;

    @Schema(description = "채널 타입", example = "SMS")
    private ChannelType channelType;

    @Schema(description = "발송 타입", example = "IMMEDIATE")
    private SendType sendType;

    @Schema(description = "수신자", example = "윤교희")
    private String recipient;

    @Schema(description = "제목", example = "제목1")
    private String title;

    @Schema(description = "내용", example = "내용1")
    private String content;
}
