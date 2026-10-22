package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.dto.ProductNameDto;
import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    @Query("SELECT t FROM Product t" +
            "   JOIN FETCH t.partner " +
            "WHERE t.partner.partnerCode = :partnerCode")
    List<Product> findActiveByPartnerCode(@Param("partnerCode") PartnerCode partnerCode);

    @Query("SELECT t FROM Product t " +
            "WHERE t.partner.partnerCode = :partnerCode " +
            "   AND t.loanType = :loanType " +
            "   AND t.active = true")
    List<Product> findActiveByPartnerCodeAndLoanType(
            @Param("partnerCode") PartnerCode partnerCode,
            @Param("loanType") LoanType loanType
    );

    @Query("""
            SELECT com.ghyinc.finance.domain.loan.dto.ProductNameDto(
                p.partner.partnerCode,
                p.productCode,
                p.productName
            )
            FROM Product p
            WHERE p.productCode IN :productCodes
           """)
    List<ProductNameDto> findNamesByProductCodes(@Param("productCodes") Collection<String> productCodes);
}
