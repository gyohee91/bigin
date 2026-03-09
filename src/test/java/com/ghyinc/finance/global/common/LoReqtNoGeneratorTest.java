package com.ghyinc.finance.global.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class LoReqtNoGeneratorTest {
    private LoReqtNoGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new LoReqtNoGenerator();
    }

    @Test
    void generate() {
        String loReqtNo = generator.generate();
        System.out.println(loReqtNo);
    }

    @Test
    @DisplayName("동시 요청 1000건 - 중복없음")
    void generate_noDuplicate() throws InterruptedException {
        int threadCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<String> results = ConcurrentHashMap.newKeySet();

        for(int i = 0; i < threadCount; i ++) {
            executorService.submit(() -> {
                try {
                    results.add(generator.generate());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        assertThat(results).hasSize(threadCount);
    }
}