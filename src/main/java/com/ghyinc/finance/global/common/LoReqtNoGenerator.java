package com.ghyinc.finance.global.common;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 한도조회 상품별 신청번호 채번
 */
@Component
public class LoReqtNoGenerator {

    public String generate(String prefix) {
        String date = DateUtils.toDateString(LocalDateTime.now());
        String uuid = UUID.randomUUID().toString()
                .replaceAll("-", "")
                .substring(0, 8)
                .toLowerCase();
        return prefix.concat(date).concat(uuid);
    }
}
