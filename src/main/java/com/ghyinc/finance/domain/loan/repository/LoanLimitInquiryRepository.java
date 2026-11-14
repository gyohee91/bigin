package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface LoanLimitInquiryRepository extends JpaRepository<LoanLimitInquiry, Long> {
    boolean existsByUserIdAndLoanTypeAndStatusIn(
            @Param("userId") Long userId,
            @Param("loanType") LoanType loanType,
            @Param("statuses") Collection<InquiryStatus> statuses
    );

    @Query("SELECT DISTINCT t FROM LoanLimitInquiry t " +
            "   LEFT JOIN FETCH t.productResults " +
            "WHERE t.inquiryNo = :inquiryNo ")
    Optional<LoanLimitInquiry> findByInquiryNoWithProductResults(@Param("inquiryNo") String inquiryNo);

    Optional<LoanLimitInquiry> findByInquiryNo(@Param("inquiryNo") String inquiryNo);

    /**
     * 당일 조회 슬롯 사용 여부. FAILED 로 슬롯이 해제된 건은 {@code dedup_key} 가 null 이라 포함되지 않는다.
     * 빠른 사전 확인용이며, 정합성은 {@code uk_inquiry_dedup_key} unique 제약이 보장한다.
     */
    boolean existsByDedupKey(String dedupKey);

    /**
     * 상태 CAS 전이. 현재 상태가 {@code statusFrom} 일 때만 {@code statusTo} 로 바꾼다.
     * 예) 선점 완료: {@code transit(inquiryNo, ACCEPTED, PENDING)}
     *
     * @return 갱신 건수. 0 이면 이미 다른 주체가 상태를 바꾼 것이므로 호출자는 중단해야 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE LoanLimitInquiry t
           SET t.status = :statusTo
           WHERE t.inquiryNo = :inquiryNo AND t.status = :statusFrom
           """)
    int transit(
            @Param("inquiryNo") String inquiryNo,
            @Param("statusFrom") InquiryStatus statusFrom,
            @Param("statusTo") InquiryStatus statusTo
    );

    /**
     * 선점 반납. ACCEPTED 인 건만 FAILED 로 바꾸고 {@code dedup_key} 를 비워 당일 재조회를 허용한다.
     * 벌크 UPDATE 는 엔티티의 {@code updateInquiryStatus} 를 거치지 않으므로 dedup_key 해제를 쿼리에서 직접 한다.
     *
     * @return 갱신 건수 (이미 PENDING 이상으로 진행된 건은 0)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE LoanLimitInquiry t
           SET t.status = com.ghyinc.finance.domain.loan.enums.InquiryStatus.FAILED, t.dedupKey = null
           WHERE t.inquiryNo = :inquiryNo AND t.status = com.ghyinc.finance.domain.loan.enums.InquiryStatus.ACCEPTED
           """)
    int failIfAccepted(@Param("inquiryNo") String inquiryNo);
}
