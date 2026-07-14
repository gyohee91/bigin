package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.ProductCache;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

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
}
