package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.ProductCache;
import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import com.ghyinc.finance.global.lock.RedisLockExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

/**
 * ProductService.getActiveProducts() 콜드 캐시 동시요청("cache stampede") 실측 테스트.
 *
 * <p>이력서 "Redisson 분산 락 기반 캐시 스탬피드 방지" 항목의 정량화 근거를 만들기 위한
 * 실측 재현 테스트다. 목적은 딱 하나:
 * 캐시가 비어있는 상태에서 동일 (partnerCode, loanType) 키로 N개 요청이 완전히 동시에
 * 들어왔을 때, {@code productRepository.findActiveByPartnerCodeAndLoanType()}(=DB 조회)가
 * 실제로 몇 번 실행되는지를 before(순수 {@code @Cacheable})/after(Redisson 이중 체크 락)
 * 두 버전에서 각각 세는 것.</p>
 *
 * <h3>실행 방법 — 반드시 로컬 Redis가 떠 있어야 한다(다른 재현 테스트들과 동일)</h3>
 * <pre>
 * 1) Before 측정:
 *    git checkout 5b395cdde85949ced5ddef50ba795adcd6ec675b^ -- src/main/java/com/ghyinc/finance/domain/loan/service/ProductService.java
 *    이 테스트 파일을 src/test/.../service/ProductServiceStampedeTest.java 에 둔 채로
 *    ./gradlew test --tests "*ProductServiceStampedeTest" -Dredis.host=localhost -Dredis.port=6379
 *    (또는 IDE에서 이 테스트 클래스만 실행)
 *
 * 2) After 측정:
 *    git checkout HEAD -- src/main/java/com/ghyinc/finance/domain/loan/service/ProductService.java
 *    동일 명령으로 재실행
 * </pre>
 *
 * <p>이 테스트는 "성공/실패"를 판정하지 않는다(둘 다 컴파일·실행은 정상적으로 된다) — 표준출력에
 * 찍히는 JSON 요약(trial별 DB 호출 횟수 분포)을 읽어 before/after를 비교하는 용도다. DB는 실제로
 * 붙이지 않고 {@code ProductRepository}를 Mockito mock으로 대체해 "DB 조회 호출 횟수"만 정확히
 * 카운트한다 — 인위적으로 200ms 지연을 줘서 실제 DB 왕복시간 동안 레이스 윈도우가 벌어지도록 했다
 * (지연이 0에 가까우면 스레드 스케줄링만으로는 스탬피드가 거의 재현되지 않는다).</p>
 *
 * <p>{@code @ComponentScan}으로 {@code ProductService}를 그대로 스캔하기 때문에, before/after
 * 어느 버전이 컴파일돼 있든(생성자 파라미터가 다르더라도) 이 테스트 코드를 전혀 수정하지 않고
 * 그대로 재사용할 수 있다 — Rate Limiter 재현 테스트 때와 동일하게 "파일 한 개만 swap, 테스트는
 * 고정" 방식.</p>
 */
@SpringJUnitConfig(ProductServiceStampedeTest.TestConfig.class)
class ProductServiceStampedeTest {

    private static final int TRIALS = 20;
    private static final int CONCURRENCY = 20;
    private static final long SIMULATED_DB_LATENCY_MS = 200;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CacheManager cacheManager;

    @Configuration
    @EnableCaching
    @ComponentScan(
            basePackageClasses = ProductService.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ProductService.class)
    )
    static class TestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("products");
        }

        @Bean
        ProductRepository productRepository() {
            return mock(ProductRepository.class);
        }

        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient() {
            String host = System.getProperty("redis.host", "localhost");
            String port = System.getProperty("redis.port", "6379");
            Config config = new Config();
            config.useSingleServer().setAddress("redis://" + host + ":" + port);
            return Redisson.create(config);
        }

        @Bean
        RedisLockExecutor redisLockExecutor(RedissonClient redissonClient) {
            return new RedisLockExecutor(redissonClient);
        }
    }

    @BeforeEach
    void resetState() {
        reset(productRepository);
        cacheManager.getCache("products").clear();
    }

    @AfterEach
    void clearCacheAfter() {
        cacheManager.getCache("products").clear();
    }

    @Test
    @DisplayName("콜드 캐시에 동시 요청 N건 발생 시 실제 DB 조회 실행 횟수 실측")
    void concurrentColdCacheRequests_measuresRealDbCallCount() throws Exception {
        List<Integer> dbCallCountPerTrial = new ArrayList<>();

        // ProductService.getActiveProducts()는 결과가 비어있으면 캐시에 안 넣는다
        // (@Cacheable(unless = "#result.isEmpty()")를 승계한 의도된 동작 - loadActiveProducts() 안의
        // `if (!result.isEmpty()) cache.put(...)` 참고). mock이 빈 리스트를 반환하면 캐시가 절대
        // 채워지지 않아서 before/after 둘 다 항상 전원 DB 호출로 나오는 무의미한 결과가 된다.
        // 그래서 실제로 캐시에 들어갈 수 있는 비어있지 않은 결과를 만들어 반환한다.
        Partner testPartner = Partner.builder()
                .partnerCode(PartnerCode.KAKAO_BANK)
                .build();
        Product testProduct = Product.builder()
                .id(1L)
                .partner(testPartner)
                .loanType(LoanType.PERSONAL_CREDIT)
                .productCode("TEST_PRODUCT_001")
                .productName("테스트상품")
                .active(true)
                .build();

        for (int trial = 0; trial < TRIALS; trial++) {
            reset(productRepository);
            cacheManager.getCache("products").clear();

            AtomicInteger dbCallCount = new AtomicInteger(0);
            given(productRepository.findActiveByPartnerCodeAndLoanType(any(), any()))
                    .willAnswer(invocation -> {
                        dbCallCount.incrementAndGet();
                        Thread.sleep(SIMULATED_DB_LATENCY_MS);
                        return List.of(testProduct);
                    });

            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
            CountDownLatch ready = new CountDownLatch(CONCURRENCY);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    productService.getActiveProducts(PartnerCode.KAKAO_BANK, LoanType.PERSONAL_CREDIT);
                    return null;
                }));
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            pool.shutdown();

            dbCallCountPerTrial.add(dbCallCount.get());
        }

        int min = dbCallCountPerTrial.stream().mapToInt(Integer::intValue).min().orElse(-1);
        int max = dbCallCountPerTrial.stream().mapToInt(Integer::intValue).max().orElse(-1);
        double avg = dbCallCountPerTrial.stream().mapToInt(Integer::intValue).average().orElse(-1);
        long trialsWithMoreThanOneCall = dbCallCountPerTrial.stream().filter(c -> c > 1).count();

        String summary = "\n=== PRODUCT SERVICE STAMPEDE TEST SUMMARY ===\n" +
                "{\n" +
                "  \"trials\": " + TRIALS + ",\n" +
                "  \"concurrencyPerTrial\": " + CONCURRENCY + ",\n" +
                "  \"perTrialDbCallCounts\": " + dbCallCountPerTrial + ",\n" +
                "  \"minDbCallsPerTrial\": " + min + ",\n" +
                "  \"maxDbCallsPerTrial\": " + max + ",\n" +
                "  \"avgDbCallsPerTrial\": " + avg + ",\n" +
                "  \"trialsWithMoreThanOneDbCall\": " + trialsWithMoreThanOneCall + "\n" +
                "}\n" +
                "===============================================\n";
        System.out.println(summary);

        // 값 판정이 아니라 실행 무결성만 확인 (before/after 둘 다 정상 종료되어야 수치를 신뢰할 수 있음)
        org.assertj.core.api.Assertions.assertThat(dbCallCountPerTrial).hasSize(TRIALS);
        org.assertj.core.api.Assertions.assertThat(min).isGreaterThanOrEqualTo(1);
    }
}
