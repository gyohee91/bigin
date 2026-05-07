package com.ghyinc.finance.global.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;
import java.util.Objects;

/**
 * MDC 복사 후 작업 실행
 */
public class MdcTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        // 호출 스레드의 MDC 스냅샷 복사
        Map<String, String> parentMdcContext = MDC.getCopyOfContextMap();
        return () -> {
            try {
                // 작업 Thread에 MDC 복원
                if(Objects.nonNull(parentMdcContext)) {
                    MDC.setContextMap(parentMdcContext);
                }
                runnable.run();
            } finally {
                // 스레드풀 스레드는 재사용되므로 반드시 초기화
                MDC.clear();
            }
        };
    }
}
