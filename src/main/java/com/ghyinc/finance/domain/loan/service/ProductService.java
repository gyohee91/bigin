package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.ProductCache;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import com.ghyinc.finance.global.exception.LockUnavailableException;
import com.ghyinc.finance.global.lock.RedisLockExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {
    private final RedisLockExecutor lockExecutor;

    private final ProductRepository productRepository;
    private final CacheManager cacheManager;

    private static final String LOCK_PREFIX = "lock:products:";
    private static final String CACHE_NAME = "products";

    public List<ProductCache> getActiveProducts(PartnerCode partnerCode, LoanType loanType) {
        String lockKey = partnerCode.name() + ":" + loanType.name();
        Cache cache = cacheManager.getCache("products");
        if (cache == null) {
            return this.loadActiveProducts(partnerCode, loanType);
        }

        List<ProductCache> cached = this.readCache(cache, lockKey);
        if (cached != null) return cached;

        try {
            return lockExecutor.execute(LOCK_PREFIX + lockKey, 3, 5,
                    () -> {
                        // 더블 체크 - 락 대기 중 다른 Thread가 이미 채웠을 수도 있음
                        List<ProductCache> doubleChecked = this.readCache(cache, lockKey);
                        if (doubleChecked != null) return doubleChecked;

                        List<ProductCache> result = this.loadActiveProducts(partnerCode, loanType);
                        if (!result.isEmpty()) this.writeCache(cache, lockKey, result);
                        return result;
                    },
                    () -> this.loadActiveProducts(partnerCode, loanType)
            );
        } catch (LockUnavailableException e) {
            // 락은 성능 최적화용이므로 Redis 장애 시 DB로 fail-open
            log.warn("[상품 캐시] 락 불가(Redis 장애), DB 직접 조회 key={}", lockKey);
            return this.loadActiveProducts(partnerCode, loanType);
        }
    }

    private List<ProductCache> loadActiveProducts(PartnerCode partnerCode, LoanType loanType) {
        return productRepository.findActiveByPartnerCodeAndLoanType(partnerCode, loanType)
                .stream()
                .map(ProductCache::from)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<ProductCache> readCache(Cache cache, String key) {
        try {
            return cache.get(key, List.class);
        } catch (RuntimeException e) {  // 연결 장애, 역직렬화 실패 -> 캐시 미스로 취급
            log.warn("[상품 캐시] 조회 실패, 미스로 처리 key={}", key, e);
            return null;
        }
    }

    private void writeCache(Cache cache, String key, List<ProductCache> result) {
        try {
            cache.put(key, result);
        } catch (RuntimeException e) {
            log.warn("[상품 캐시] 저장 실패 (무시) key={}", key, e);
        }
    }

    @Transactional
    public void updateProductStatus(ProductCache productCache, boolean active) {
        productRepository.findById(productCache.getId())
                .ifPresent(product -> product.changeActive(active));

        // 커밋 이후에 evict: 커밋 전에 지우면 다른 요청이 옛 값을 다시 캐시에 넣을 수 있음
        String key = productCache.getPartnerCode().name() + ":" + productCache.getLoanType().name();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictCache(key);
            }
        });
    }

    private void evictCache(String key) {
        try {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null)  cache.evict(key);
        } catch (RuntimeException e) {
            log.warn("[상품 캐시] evict 실패, TTL 만료까지 이전 값 유지 key={}", key, e);
        }
    }

    @CacheEvict(value = "products", allEntries = true)
    public void evictAllProductCache() {
        log.info("전체 상품 캐시 초기화");
    }
}
