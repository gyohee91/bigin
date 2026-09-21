package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoanLimitProductResultRepository extends JpaRepository<LoanLimitProductResult, Long> {
    Optional<LoanLimitProductResult> findByLoReqtNoAndProductCode(
            @Param("loReqtNo") String loReqtNo,
            @Param("productCode") String productCode
    );

    Optional<LoanLimitProductResult> findByLoReqtNoAndPartnerCodeAndProductCode(
            @Param("loReqtNo") String loReqtNo,
            @Param("partnerCode") PartnerCode partnerCode,
            @Param("productCode") String productCode
    );

    /**
     * 콜백 수신 카운트를 DB 레벨 원자적 UPDATE로 증가시킨다.
     *
     * <p>과거에는 {@code @Lock(PESSIMISTIC_WRITE)}로 부모 {@code LoanLimitInquiry} 행을 잠그고
     * 메서드 레벨 트랜잭션 안에서 read-modify-write 했는데, 파트너 콜백이 같은 inquiry에
     * 동시에 몰리면(46개 파트너 fan-out) 전부 그 한 행의 락을 놓고 대기하면서 대기 중인
     * 스레드마다 Hikari 커넥션을 하나씩 물고 있게 되어 부하테스트에서 커넥션 풀 고갈의
     * 직접 원인이 됐다. 이 UPDATE 문 자체는 원자적이라 별도 애플리케이션 락이 필요 없고,
     * 로우 직렬화 범위도 이 한 문장 실행 시간으로 줄어든다.</p>
     *
     * @return 갱신된 row 수 (0이면 해당 loReqtNo/productCode의 Inquiry를 못 찾은 것)
     */
    @Modifying
    @Query("""
            UPDATE LoanLimitInquiry i
            SET i.successProductCount = i.successProductCount + 1
            WHERE i.id = (
                SELECT t.loanLimitInquiry.id FROM LoanLimitProductResult t
                WHERE t.loReqtNo = :loReqtNo AND t.productCode = :productCode
            )
            """)
    int incrementSuccessProductCount(
            @Param("loReqtNo") String loReqtNo,
            @Param("productCode") String productCode
    );

    @Query("SELECT t.loanLimitInquiry FROM LoanLimitProductResult t WHERE t.loReqtNo = :loReqtNo AND t.productCode = :productCode")
    Optional<LoanLimitInquiry> findInquiryByLoReqtNoAndProductCode(
            @Param("loReqtNo") String loReqtNo,
            @Param("productCode") String productCode
    );

    @Query("""
            SELECT new com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto(
               t.loReqtNo,
               t.partnerCode,
               t.productCode,
               t.resultCode,
               t.amount,
               t.interestRate
            )
            FROM LoanLimitProductResult t
            WHERE t.loanLimitInquiry.id = :inquiryId
            ORDER BY t.amount DESC NULLS LAST
            """)
    Page<LoanLimitProductResultDto> findProductResultsByInquiryId(@Param("inquiryId") Long inquiryId, Pageable pageable);

    @Query("""
            SELECT new com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultResponse(
                t.loReqtNo,
                t.partnerCode,
                t.productCode,
                t.status,
                t.resultCode,
                t.amount,
                t.interestRate
            )
            FROM LoanLimitProductResult t
            WHERE t.loanLimitInquiry.id = :inquiryId
            """)
    List<LoanLimitProductResultResponse> findAllByInquiryNo(@Param("inquiryId") Long inquiryId);
}
