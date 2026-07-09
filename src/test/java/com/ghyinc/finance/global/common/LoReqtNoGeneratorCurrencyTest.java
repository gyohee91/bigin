package com.ghyinc.finance.global.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration(exclude = {
        KafkaAutoConfiguration.class
})
@ImportAutoConfiguration(RedisAutoConfiguration.class)
@Import(LoReqtNoGenerator.class)
class LoReqtNoGeneratorCurrencyTest {
    @Autowired
    private LoReqtNoGenerator generator;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("동시 요청 1000건 - Redis INCR 기반 중복 없음")
    void generate_current_noDuplicate() throws InterruptedException {
        // 테스트 메서드 내부에서 직접 키 초기화 (외부 간섭 차단)
        String date = DateUtils.toDateString(LocalDateTime.now());
        redisTemplate.delete("SEQ:LR:" + date);

        int threadCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);       // 전 스레드 동시 출발 신호
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        Set<String> results = ConcurrentHashMap.newKeySet();
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();   // 모든 스레드가 준비된 후 동시 출발
                    results.add(generator.generate("LR"));
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 1000개 스레드 동시 출발
        doneLatch.await();
        executorService.shutdown();

        // then
        assertThat(failCount.get()).isEqualTo(0);
        assertThat(results).hasSize(threadCount);
        assertThat(results).allMatch(r -> r.matches("LR\\d{8}\\d{5}"));
    }

    @Test
    @DisplayName("서로 다른 prefix 동시 채번 시 각각 독립적으로 중복 없음")
    void generate_differentPrefix_concurrent_noDuplicate() throws InterruptedException {
        // given
        int threadCount = 500;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount * 2);
        Set<String> lrResults = ConcurrentHashMap.newKeySet();
        Set<String> llResults = ConcurrentHashMap.newKeySet();

        // when
        for(int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    lrResults.add(generator.generate("LR"));
                } finally {
                    latch.countDown();
                }
            });
            executorService.submit(() -> {
                try {
                    llResults.add(generator.generate("LL"));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // then
        assertThat(lrResults).hasSize(threadCount);
        assertThat(llResults).hasSize(threadCount);

        // LR과 LL 채번 결과가 겹치지 않음
        lrResults.retainAll(llResults);
        assertThat(lrResults).isEmpty();
    }
}