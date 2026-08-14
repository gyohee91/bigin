package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.ProductCache;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import com.ghyinc.finance.global.lock.RedisLockExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public List<ProductCache> getActiveProducts(PartnerCode partnerCode, LoanType loanType) {
        String lockKey = partnerCode.name() + ":" + loanType.name();
        Cache cache = cacheManager.getCache("products");
        if (cache == null) {
            return this.loadActiveProducts(partnerCode, loanType);
        }

        List<ProductCache> cached = cache.get(lockKey, List.class);
        if (cached != null) return cached;

        return lockExecutor.execute(LOCK_PREFIX + lockKey, 3, 5,
                () -> {
                    // 더블 체크 - 락 대기 중 다른 Thread가 이미 채웠을 수도 있음
                    List<ProductCache> doubleChecked = cache.get(lockKey, List.class);
                    if (doubleChecked != null) return doubleChecked;

                    List<ProductCache> result = this.loadActiveProducts(partnerCode, loanType);
                    if (!result.isEmpty()) cache.put(lockKey, result);
                    return result;
                },
                () -> this.loadActiveProducts(partnerCode, loanType)
        );
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
