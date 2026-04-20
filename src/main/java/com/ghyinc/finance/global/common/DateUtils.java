package com.ghyinc.finance.global.common;

import lombok.experimental.UtilityClass;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 날짜 시간 변환, 포맷팅
 */
@UtilityClass
public class DateUtils {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * LocalDateTime -> yyyyMMddHHmmss 변환
     * @param date  LocalDateTime
     * @return      String (yyyyMMddHHmmss)
     */
    public String toDateTimeString(LocalDateTime date) {
        return date.format(DATE_TIME_FORMATTER);
    }

    /**
     * LocalDateTime -> yyyyMMdd 변환
     * @param date  LocalDateTime
     * @return      String (yyyyMMdd)
     */
    public String toDateString(LocalDateTime date) {
        return date.format(DATE_FORMATTER);
    }
}
