package com.ghyinc.finance.global.common;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class LoReqtNoGenerator {
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    public String generate(String prefix) {
        String date = LocalDateTime.now().format(DATE_FORMATTER);
        String uuid = UUID.randomUUID().toString()
                .replaceAll("-", "")
                .substring(0, 8)
                .toLowerCase();
        return prefix.concat(date).concat(uuid);
    }
}
