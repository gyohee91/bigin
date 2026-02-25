package com.ghyinc.finance.domain.notification.controller;

import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.dto.NotificationSendResponse;
import com.ghyinc.finance.domain.notification.service.NotificationService;
import com.ghyinc.finance.global.common.ApiCommResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림 API", description = "알림 발송 등록 및 내역 조회")
@Slf4j
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;

    @Operation(
            summary = "알림 발송 등록",
            description = "즉시 발송 또는 예약 발송 알림을 등록합니다"
    )
    @ApiResponses(
            @ApiResponse(
                    responseCode = "200",
                    description = "알림 발송 성공",
                    content = @Content(schema = @Schema(implementation = NotificationSendResponse.class))
            )
    )
    @PostMapping("/send")
    public ResponseEntity<ApiCommResponse<NotificationSendResponse>> sendNotification(
            @Valid @RequestBody NotificationSendRequest request
    ) {
        NotificationSendResponse response = notificationService.sendNotification(request);

        return ResponseEntity.ok(ApiCommResponse.success("발송 성공", response));
    }
}
