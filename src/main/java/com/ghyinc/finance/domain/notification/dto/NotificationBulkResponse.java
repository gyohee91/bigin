package com.ghyinc.finance.domain.notification.dto;

import lombok.Builder;

@Builder
public record NotificationBulkResponse(
        int notified,
        int skipped
) {
    public static NotificationBulkResponse from(int notified, int skipped) {
        return NotificationBulkResponse.builder()
                .notified(notified)
                .skipped(skipped)
                .build();
    }
}
