package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    @Query("SELECT t FROM Product t" +
            "   JOIN FETCH t.partner " +
            "WHERE t.partner.partnerCode = :partnerCode")
    List<Product> findActiveByPartnerCode(PartnerCode partnerCode);
}
