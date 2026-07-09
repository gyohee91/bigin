package com.ghyinc.finance.global.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class LoReqtNoGeneratorTest {
    @Mock
    private StringRedisTemplate redisTemplate;

    @Captor
    private ArgumentCaptor<List<String>> keysCaptor;

    private LoReqtNoGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new LoReqtNoGenerator(redisTemplate);
    }

    @Test
    @DisplayName("채번 결과가 prefix + yyyyMMdd + 5자리 숫자 형식인지 검증")
    void generate_returnsCorrectFormat() {
        // given
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willReturn(1L);

        // when
        String loReqtNo = generator.generate("LR");

        // then
        assertThat(loReqtNo).matches("LR\\d{8}\\d{5}");
        assertThat(loReqtNo).hasSize(15);
        assertThat(loReqtNo).startsWith("LR");
    }

    @Test
    @DisplayName("Lua 스크립트로 INCR + TTL 설정이 원자적으로 1회 호출됨")
    void generate_firstSeq_setsTTL() {
        // given
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willReturn(1L);

        // when
        generator.generate("LR");

        // then - Lua 스크립트(INCR+EXPIRE 원자 처리)가 1회 호출됨
        then(redisTemplate).should(times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        then(redisTemplate).should(never()).expire(any(), any());
    }

    @Test
    @DisplayName("첫 채번이 아니어도 expire()가 별도 호출되지 않음")
    void generate_notFirstSeq_noTTL() {
        // given
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willReturn(2L);

        // when
        generator.generate("LR");

        // then - TTL은 Lua 스크립트 내부에서 처리하므로 Java 레벨에서 expire 미호출
        then(redisTemplate).should(never()).expire(any(), any());
    }

    @Test
    @DisplayName("loReqtNo와 inquiryNo 채번 시 독립적인 Redis Key 사용")
    void generate_differentPrefix_usesDifferentRedisKey() {
        // given
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).willReturn(1L);

        // when
        generator.generate("LR");
        generator.generate("LL");

        // then - 서로 다른 key로 execute 호출
        then(redisTemplate).should(times(2)).execute(any(RedisScript.class), keysCaptor.capture(), any(Object[].class));

        List<List<String>> allKeys = keysCaptor.getAllValues();
        String key1 = allKeys.get(0).get(0);
        String key2 = allKeys.get(1).get(0);
        assertThat(key1).contains("LR");
        assertThat(key2).contains("LL");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    @DisplayName("동시 요청 1000건 - 중복없음")
    void generate_noDuplicate() throws InterruptedException {
        // given - execute()가 호출될 때마다 1씩 증가하는 값 반환
        AtomicLong counter = new AtomicLong(0);
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .willAnswer(invocation -> counter.incrementAndGet());

        int threadCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<String> results = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    results.add(generator.generate("LL"));
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