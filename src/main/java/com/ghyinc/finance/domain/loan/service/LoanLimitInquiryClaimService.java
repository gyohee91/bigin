package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis 장애 시 사용하는 DB 선점(claim) 전용 협력자.
 *
 * <p>호출자({@link LoanLimitInquiryCreator})는 외부 데이터 조회처럼 오래 걸리는 작업을 트랜잭션 밖에서 수행하므로,
 * 선점과 반납은 각각 {@code REQUIRES_NEW} 짧은 트랜잭션으로 즉시 커밋한다.
 * 별도 빈으로 분리한 이유는 같은 클래스 내부 호출이 AOP 프록시를 우회해 {@code @Transactional}이
 * 무효화되기 때문이다.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanLimitInquiryClaimService {
    private final LoReqtNoGenerator generator;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;

    /**
     * 당일 조회 슬롯을 선점한다. ACCEPTED 상태의 최소 행(개인정보 없음)을 INSERT 하고 즉시 커밋한다.
     *
     * <p>{@code saveAndFlush}로 이 트랜잭션 안에서 unique 위반을 바로 드러낸다.
     * 이미 같은 {@code dedupKey} 가 있으면 {@code DataIntegrityViolationException} 이 그대로 전파되며,
     * 중복 여부 판정은 호출자가 한다. inquiryNo 는 Redis 에 의존하지 않는
     * {@code generateGuid} 로 채번한다. (Redis 장애 경로이므로)</p>
     *
     * @return 선점한 조회 식별번호
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String claim(Long userId, LoanType loanType, String dedupKey) {
        String inquiryNo = generator.generate("LL");
        loanLimitInquiryRepository.saveAndFlush(
                LoanLimitInquiry.builder()
                        .inquiryNo(inquiryNo)
                        .userId(userId)
                        .loanType(loanType)
                        .status(InquiryStatus.ACCEPTED)
                        .dedupKey(dedupKey)
                        .build()
        );
        return inquiryNo;
    }

    /**
     * 선점을 반납한다. 아직 ACCEPTED 인 경우에만 FAILED 로 바꾸고 {@code dedup_key} 를 비운다. (CAS)
     *
     * <p>이미 PENDING 으로 완료됐다면 0건 갱신이라 아무것도 바꾸지 않는다. 반납 후에는 같은 날 재조회가 가능하다.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(String inquiryNo) {
        loanLimitInquiryRepository.failIfAccepted(inquiryNo);
    }
}
