package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.ProductCache;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final RedissonClient redissonClient;
    private final CacheManager cacheManager;

    private static final String LOCK_PREFIX = "lock:products:";

    public List<ProductCache> getActiveProducts(PartnerCode partnerCode, LoanType loanType) {
        String key = partnerCode.name() + ":" + loanType.name();
        Cache cache = cacheManager.getCache("products");
        if (cache == null) {
            return this.loadActiveProducts(partnerCode, loanType);
        }

        List<ProductCache> cached = cache.get(key, List.class);
        if (cached != null) {
            return cached;
        }

        RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                // 락 획득 실패 시 갱신 없이 DB 직접 조회로 Fallback
                return this.loadActiveProducts(partnerCode, loanType);
            }

            // 더블 체크 - 락 대기 중 다른 Thread가 이미 채웠을 수도 있음
            cached = cache.get(key, List.class);
            if (cached != null) {
                return cached;
            }

            List<ProductCache> result = this.loadActiveProducts(partnerCode, loanType);
            if(!result.isEmpty()) {
                cache.put(key, result);
            }

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return this.loadActiveProducts(partnerCode, loanType);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

    }

    /*
    @Cacheable(
            value = "products",
            key = "#partnerCode.name() + ':' + #loanType.name()",
            unless = "#result.isEmpty()"
    )
    */
    private List<ProductCache> loadActiveProducts(PartnerCode partnerCode, LoanType loanType) {
        return productRepository.findActiveByPartnerCodeAndLoanType(partnerCode, loanType)
                .stream()
                .map(ProductCache::from)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Transactional
    @CacheEvict(
            value = "products",
            key = "#product.partnerCode.name() + ':' + #product.loanType.name()"
    )
    public void updateProductStatus(ProductCache productCache, boolean active) {
        productRepository.findById(productCache.getId())
                .ifPresent(product -> product.changeActive(active));
    }

    @CacheEvict(value = "products", allEntries = true)
    public void evictAllProductCache() {
        log.info("전체 상품 캐시 초기화");
    }
}
