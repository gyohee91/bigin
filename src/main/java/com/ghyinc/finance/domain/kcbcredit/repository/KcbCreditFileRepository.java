package com.ghyinc.finance.domain.kcbcredit.repository;

import com.ghyinc.finance.domain.kcbcredit.entity.KcbCreditFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KcbCreditFileRepository extends JpaRepository<KcbCreditFile, Long> {
    Optional<KcbCreditFile> findByFileName(String fileName);
}
