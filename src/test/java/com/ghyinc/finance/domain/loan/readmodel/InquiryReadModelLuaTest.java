package com.ghyinc.finance.domain.loan.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lua 스크립트 의미 검증. Redis 로컬 필요
 */
class InquiryReadModelLuaTest {
    static RedissonClient redissonClient;
    static StringRedisTemplate redis;
    InquiryReadModelStore store;
    String loReqtNo;

    @BeforeAll
    static void connect() {
        try {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:6379")
                    .setConnectTimeout(500)
                    .setTimeout(300)
                    .setRetryAttempts(0);
            redissonClient = Redisson.create(config);

            StringRedisTemplate redisTemplate = new StringRedisTemplate(new RedissonConnectionFactory(redissonClient));
            redisTemplate.afterPropertiesSet();
            redisTemplate.getConnectionFactory().getConnection().ping();
            redis = redisTemplate;
        } catch (Exception e) {
            if (redissonClient != null) redissonClient.shutdown();
            assumeTrue(false, "로컬 Redis 없음 -> skip");
        }
    }

    @BeforeEach
    void setUp() {
        store = new InquiryReadModelStore(redis, new ObjectMapper(), new SimpleMeterRegistry(), true, 60, 15, 3000);
        loReqtNo = "TEST-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        String key = "poll:{" + loReqtNo + "}:";
        redis.delete(List.of(key + "meta", key + "rank", key + "items", key + "stg"));
    }

    @AfterAll
    static void disconnect() {
        if (redissonClient != null) redissonClient.shutdown();
    }

    private LoanLimitProductResultDto dto(String loReqtNo, PartnerInquiryStatus staus, Long amount) {
        return new LoanLimitProductResultDto(loReqtNo, PartnerCode.KAKAO_BANK, "k1", staus, null, amount, 5.5);
    }

    private String metaKey() {
        return "poll:{" + loReqtNo + "}:meta";
    }

    @Test
    @DisplayName("meta가 없으면 delta는 아무 것도 만들지 않는다")
    void delta_doesNotCreateMeta_whenMetaMissing() {
        store.upsert(loReqtNo, false, -1, null, List.of(this.dto("LR1", PartnerInquiryStatus.SUCCESS, 100L)));

        assertThat(redis.hasKey(this.metaKey())).isFalse();
        assertThat(store.read(loReqtNo, null, 0, 20).type()).isEqualTo(InquiryReadModelStore.Type.MISS);
    }

    @Test
    @DisplayName("금액 내림차순, null은 맨 뒤, 페이징")
    void read_ordersByAmountDescNullLast_AndPages() {
        store.upsert(loReqtNo, true, 3, "IN_PROGRESS", List.of(
                this.dto("LR1", PartnerInquiryStatus.SEND_SUCCESS, 100L),
                this.dto("LR2", PartnerInquiryStatus.SEND_SUCCESS, null),
                this.dto("LR3", PartnerInquiryStatus.SEND_SUCCESS, 300L)
        ));

        InquiryReadModelStore.ReadResult readResult0 = store.read(loReqtNo, null, 0, 2);
        InquiryReadModelStore.ReadResult readResult1 = store.read(loReqtNo, null, 1, 2);

        assertThat(readResult0.type()).isEqualTo(InquiryReadModelStore.Type.OK);
        assertThat(readResult0.card()).isEqualTo(3L);
        assertThat(readResult0.itemsJson()).hasSize(2);
        assertThat(readResult0.itemsJson().get(0)).contains("\"LR3\"");
        assertThat(readResult0.itemsJson().get(1)).contains("\"LR1\"");
        assertThat(readResult1.itemsJson()).hasSize(1);
        assertThat(readResult1.itemsJson().get(0)).contains("\"LR2\"");
    }

    @Test
    @DisplayName("같은 version이면 SAME, 변경이 있으면 version이 증가한다")
    void version_staysOnNoChange_andIncreasesOnChange() {
        store.upsert(loReqtNo, true, 1, "IN_PROGRESS", List.of(this.dto("LR1", PartnerInquiryStatus.SEND_SUCCESS, null)));
        InquiryReadModelStore.ReadResult firstResult = store.read(loReqtNo, null, 0, 20);
        assertThat(store.read(loReqtNo, firstResult.version(), 0, 20).type()).isEqualTo(InquiryReadModelStore.Type.SAME);

        // 동일 내용 재적용 -> 변경 없음 -> version 유지
        store.upsert(loReqtNo, false, -1, null, List.of(this.dto("LR1", PartnerInquiryStatus.SEND_SUCCESS, null)));
        assertThat(store.read(loReqtNo, firstResult.version(), 0, 20).type()).isEqualTo(InquiryReadModelStore.Type.SAME);

        // 실제 변경 -> version 증가
        store.upsert(loReqtNo, false, -1, null, List.of(this.dto("LR1", PartnerInquiryStatus.SUCCESS, 500L)));
        InquiryReadModelStore.ReadResult changedResult = store.read(loReqtNo, firstResult.version(), 0, 20);
        assertThat(changedResult.type()).isEqualTo(InquiryReadModelStore.Type.OK);
        assertThat(changedResult.version()).isGreaterThan(firstResult.version());
        assertThat(changedResult.success()).isEqualTo(1);
    }

    @Test
    @DisplayName("낮은 단계의 늦은 쓰기가 SUCCESS를 덮어쓰지 못한다")
    void stage_isNotDowngradedByLateWrite() {
        store.upsert(loReqtNo, true, 1, "IN_PROGRESS", List.of(this.dto("LR1", PartnerInquiryStatus.SEND_SUCCESS, null)));
        store.upsert(loReqtNo, false, -1, null, List.of(this.dto("LR1", PartnerInquiryStatus.SUCCESS, 500L)));
        // 뒤늦게 도착한 applyResults delta (SEND_SUCCESS)
        store.upsert(loReqtNo, false, -1, null, List.of(this.dto("LR1", PartnerInquiryStatus.SEND_SUCCESS, null)));

        InquiryReadModelStore.ReadResult readResult = store.read(loReqtNo, null, 0, 20);
        assertThat(readResult.success()).isEqualTo(1);
        assertThat(readResult.itemsJson().get(0)).contains("\"SUCCESS\"").contains("500");
    }

    @Test
    @DisplayName("전체 수신 완료되면 term이 1이다")
    void term_isOne_whenAllReceived() {
        store.upsert(loReqtNo, true, 1, "IN_PROGRESS", List.of(this.dto("LR1", PartnerInquiryStatus.SEND_SUCCESS, null)));
        assertThat(redis.opsForHash().get(this.metaKey(), "term")).isEqualTo("0");
        
        store.upsert(loReqtNo, false, -1, null, List.of(this.dto("LR1", PartnerInquiryStatus.SUCCESS, 500L)));
        assertThat(redis.opsForHash().get(this.metaKey(), "term")).isEqualTo("1");
    }

    @Test
    @DisplayName("FAILED 상태이면 term이 1이다")
    void term_isOne_whenFailed() {
        store.upsert(loReqtNo, true, 2, "IN_PROGRESS", List.of());
        store.upsert(loReqtNo, false, -1, "FAILED", List.of());

        assertThat(redis.opsForHash().get(this.metaKey(), "term")).isEqualTo("1");
        assertThat(redis.opsForHash().get(this.metaKey(), "status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("종료되지 않은 건이 reconcile 간격을 넘으면 MISS로 취급한다")
    void read_treatsAsMiss_whenReconcileIntervalExceeded() {
        store.upsert(loReqtNo, true, 2, "IN_PROGRESS", List.of(this.dto("LR1", PartnerInquiryStatus.SEND_SUCCESS, null)));
        redis.opsForHash().put(this.metaKey(), "syncedAt", String.valueOf(System.currentTimeMillis() - 60_000));

        assertThat(store.read(loReqtNo, null, 0, 20).type()).isEqualTo(InquiryReadModelStore.Type.MISS);
    }
}