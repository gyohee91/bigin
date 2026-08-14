package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.ProductCache;
import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import com.ghyinc.finance.global.lock.RedisLockExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @InjectMocks
    private ProductService productService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private RedisLockExecutor lockExecutor;

    @Mock
    private CacheManager cacheManager;

    private static final String CACHE_KEY = "KAKAO_BANK:PERSONAL_CREDIT";
    private static final String LOCK_KEY = "lock:products:" + CACHE_KEY;

    private Product buildProduct() {
        Partner partner = Partner.builder()
                .partnerCode(PartnerCode.KAKAO_BANK)
                .build();

        return Product.builder()
                .id(1L)
                .partner(partner)
                .loanType(LoanType.PERSONAL_CREDIT)
                .productCode("P001")
                .productName("신용대출A")
                .active(true)
                .build();
    }

    // 락 획득 성공 상황 - action을 그대로 실행
    @SuppressWarnings("unchecked")
    private void stubLockAcquired() {
        given(lockExecutor.execute(anyString(), anyLong(), anyLong(), any(Supplier.class), any(Supplier.class)))
                .willAnswer(invocation -> {
                    Supplier<Object> action = invocation.getArgument(3);
                    return action.get();
                });
    }

    // 락 획득 실패 상황 - onLockUnavailable(fallback)을 실행
    @SuppressWarnings("unchecked")
    private void stubLockUnavailable() {
        given(lockExecutor.execute(anyString(), anyLong(), anyLong(), any(Supplier.class), any(Supplier.class)))
                .willAnswer(invocation -> {
                    Supplier<Object> fallback = invocation.getArgument(4);
                    return fallback.get();
                });
    }

    @Test
    @DisplayName("캐시 히트 시 락/DB 조회 없이 캐시 값을 반환한다")
    void getActiveProducts_returnsCachedValue_whenCacheHit() {
        // given
        Cache cache = mock(Cache.class);
        List<ProductCache> cached = List.of(ProductCache.from(this.buildProduct()));
        given(cacheManager.getCache("products")).willReturn(cache);
        given(cache.get(CACHE_KEY, List.class)).willReturn(cached);

        // when
        List<ProductCache> result = productService.getActiveProducts(PartnerCode.KAKAO_BANK, LoanType.PERSONAL_CREDIT);

        // then - fast-path에서 반환되어 락 자체를 타지 않는다
        assertThat(result).isEqualTo(cached);
        then(lockExecutor).should(never()).execute(anyString(), anyLong(), anyLong(), any(Supplier.class), any(Supplier.class));
        then(productRepository).should(never()).findActiveByPartnerCodeAndLoanType(any(), any());
    }

    @Test
    @DisplayName("캐시 미스 + 락 획득 성공 시 DB 조회 후 캐시에 저장한다")
    void getActiveProducts_loadsFromDbAndCaches_whenLockAcquired() {
        // given
        Cache cache = mock(Cache.class);
        given(cacheManager.getCache("products")).willReturn(cache);
        given(cache.get(CACHE_KEY, List.class)).willReturn(null); // fast-path, 락 안 더블 체크 모두 미스

        this.stubLockAcquired();

        Product product = this.buildProduct();
        given(productRepository.findActiveByPartnerCodeAndLoanType(PartnerCode.KAKAO_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of(product));

        // when
        List<ProductCache> result = productService.getActiveProducts(PartnerCode.KAKAO_BANK, LoanType.PERSONAL_CREDIT);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductCode()).isEqualTo("P001");
        then(cache).should().put(eq(CACHE_KEY), anyList());
        then(lockExecutor).should().execute(eq(LOCK_KEY), eq(3L), eq(5L), any(Supplier.class), any(Supplier.class));
    }

    @Test
    @DisplayName("캐시 미스 + 락 획득 실패 시 캐시 갱신 없이 DB에서 직접 조회한다")
    void getActiveProducts_fallsBackToDb_whenLockNotAcquired() {
        // given
        Cache cache = mock(Cache.class);
        given(cacheManager.getCache("products")).willReturn(cache);
        given(cache.get(CACHE_KEY, List.class)).willReturn(null);

        this.stubLockUnavailable();

        Product product = this.buildProduct();
        given(productRepository.findActiveByPartnerCodeAndLoanType(PartnerCode.KAKAO_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of(product));

        // when
        List<ProductCache> result = productService.getActiveProducts(PartnerCode.KAKAO_BANK, LoanType.PERSONAL_CREDIT);

        // then
        assertThat(result).hasSize(1);
        then(cache).should(never()).put(any(), any());
    }

    @Test
    @DisplayName("락 획득 후 더블 체크에서 캐시가 이미 채워져 있으면 DB를 재조회하지 않는다")
    void getActiveProducts_returnsDoubleCheckedCache_whenAnotherThreadAlreadyFilled() {
        // given
        Cache cache = mock(Cache.class);
        List<ProductCache> cachedByOtherThread = List.of(ProductCache.from(this.buildProduct()));
        given(cacheManager.getCache("products")).willReturn(cache);
        given(cache.get(CACHE_KEY, List.class))
                .willReturn(null)                  // fast-path 조회 - 미스
                .willReturn(cachedByOtherThread);   // 락 획득 후 더블 체크 - 다른 스레드가 이미 채움

        this.stubLockAcquired();

        // when
        List<ProductCache> result = productService.getActiveProducts(PartnerCode.KAKAO_BANK, LoanType.PERSONAL_CREDIT);

        // then
        assertThat(result).isEqualTo(cachedByOtherThread);
        then(productRepository).should(never()).findActiveByPartnerCodeAndLoanType(any(), any());
        then(cache).should(never()).put(any(), any());
    }

    @Test
    @DisplayName("products 캐시가 등록되어 있지 않으면 락 없이 DB에서 직접 조회한다")
    void getActiveProducts_fallsBackToDb_whenCacheNotConfigured() {
        // given
        given(cacheManager.getCache("products")).willReturn(null);

        Product product = this.buildProduct();
        given(productRepository.findActiveByPartnerCodeAndLoanType(PartnerCode.KAKAO_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of(product));

        // when
        List<ProductCache> result = productService.getActiveProducts(PartnerCode.KAKAO_BANK, LoanType.PERSONAL_CREDIT);

        // then
        assertThat(result).hasSize(1);
        then(lockExecutor).should(never()).execute(anyString(), anyLong(), anyLong(), any(Supplier.class), any(Supplier.class));
    }
}
