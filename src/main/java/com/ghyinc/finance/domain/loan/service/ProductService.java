package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.ProductCache;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;

    @Cacheable(
            value = "products",
            key = "#partnerCode.name() + ':' + #loanType.name()"
    )
    public List<ProductCache> getActiveProducts(PartnerCode partnerCode, LoanType loanType) {
        return productRepository.findActiveByPartnerCodeAndLoanType(partnerCode, loanType)
                .stream()
                .map(ProductCache::from)
                .toList();
    }

    @Transactional
    @CacheEvict(
            value = "products",
            key = "#product.partnerCode.name() + ':' + #product.loanType.name()"
    )
    public void updateProductStatus(ProductCache productCache, boolean active) {
        productRepository.findById(productCache.id())
                .ifPresent(product -> {
                    product.changeActive(active);
                    productRepository.save(product);
                });
    }

    @CacheEvict(value = "products", allEntries = true)
    public void evictAllProductCache() {
        log.info("전체 상품 캐시 초기화");
    }
}
