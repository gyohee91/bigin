package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LoanLimitResultService}의 콜백 카운트 증가 전용 협력자.
 *
 * <p>{@link LoanLimitProductResultRepository#incrementSuccessProductCount}는 원자적 UPDATE문이지만,
 * 이 UPDATE가 잡는 부모 {@code LoanLimitInquiry} 행의 row lock은 문장 실행이 끝나는 시점이 아니라
 * "이 UPDATE가 속한 트랜잭션이 커밋되는 시점"까지 유지된다. 과거 {@code LoanLimitResultService
 * #responseCompareLoanResult}처럼 이 UPDATE를 호출부와 같은 메서드 레벨 {@code @Transactional} 안에서
 * 실행하면, 이후에 이어지는 {@code productResult.updateResult()}(dirty checking flush)와 outbox
 * INSERT + commit까지 전부 그 row lock을 붙든 채 진행된다. 결과적으로 동일 inquiry에 46개 파트너
 * 콜백이 동시에 몰리면 전부 그 트랜잭션 전체 시간만큼 순차 대기하게 되고, 대기 스레드마다 Hikari
 * 커넥션을 하나씩 물고 있어 부하테스트에서 커넥션 풀이 고갈되는 원인이 됐다(비관락을 제거한 뒤에도
 * 동일하게 재현됨).</p>
 *
 * <p>이 클래스는 그 UPDATE 한 문장만 별도의 {@link Propagation#REQUIRES_NEW} 트랜잭션으로 분리해서,
 * UPDATE 실행 직후 즉시 커밋되고 row lock도 그 순간 바로 풀리도록 한다. 호출부의 나머지 로직
 * ({@code productResult.updateResult()}, outbox enqueue)은 파트너·상품별로 서로 다른
 * {@code LoanLimitProductResult} 행을 다루므로 46개 콜백끼리 경합하지 않는다 - 즉 이 카운터 증가만
 * 짧게 쪼개면 충분하다.</p>
 *
 * <p>주의(Spring self-invocation): {@link LoanLimitResultService}는 반드시 이 클래스를 별도 빈으로
 * 주입받아 프록시를 통해 호출해야 {@code @Transactional(REQUIRES_NEW)}가 적용된다. 같은 클래스 안의
 * 내부 메서드 호출로 합치면 AOP 프록시를 거치지 않아 REQUIRES_NEW가 무시되고 바깥 트랜잭션에 그대로
 * 참여하게 된다.</p>
 *
 * @see LoanLimitInquiryPersistenceService 동일한 이유로 트랜잭션을 짧게 쪼갠 선례
 */
@Service
@RequiredArgsConstructor
public class LoanLimitCounterService {
    private final LoanLimitProductResultRepository loanLimitProductResultRepository;

    /**
     * 콜백 수신 카운트를 별도의 짧은 트랜잭션으로 증가시킨다.
     *
     * @return 갱신된 row 수 (0이면 해당 loReqtNo/productCode의 Inquiry를 못 찾은 것)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int incrementSuccessCount(String loReqtNo, String productCode) {
        return loanLimitProductResultRepository.incrementSuccessProductCount(loReqtNo, productCode);
    }
}
