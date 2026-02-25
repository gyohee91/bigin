package com.ghyinc.finance.global.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiCommResponse<T> {
    @Builder.Default
    private boolean success = true;
    private String message;
    private T data;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    public static <T> ApiCommResponse<T> success(String message, T data) {
        return ApiCommResponse.<T>builder()
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiCommResponse<T> fail(boolean success, String message){
        return ApiCommResponse.<T>builder()
                .success(false)
                .message(message)
                .data(null)
                .build();
    }
}
