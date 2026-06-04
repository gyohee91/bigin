package com.ghyinc.finance.domain.loan.factory;

import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 대출 유형에 따른 {@link LoanLimitStrategy} 구현체를 반환하는 팩토리.
 *
 * <p>
 * Spring의 DI를 활용하여 {@code LoanLimitStrategy} 구현체를 자동으로 수집하고
 * {@code LoanType}을 키로 하는 Map에 등록한다. 새로운 대출 유형 추가 시
 * Factory 코드 수정 없이 Strategy 구현체만 추가하면 자동으로 등록된다. (OCP 준수)
 *
 * <h3>전략 등록 방식</h3>
 * <pre>
 * Spring Context 시작
 *  → @Component가 붙은 LoanLimitStrategy 구현체 전체 수집
 *  → List&lt;LoanLimitStrategy&gt; 주입
 *  → getLoanType()을 키로 strategyMap에 등록
 *  → 서버 기동 시 등록된 전략 목록 로깅
 * </pre>
 *
 * <h3>신규 대출유형 추가 방법</h3>
 * <ol>
 *     <li>{@link LoanLimitStrategy}를 구현하는 클래스 작성</li>
 *     <li>{@code @Component} 선언으로 Spring Bean 등록</li>
 *     <li>Factory 수정 불필요 - 자동으로 strategyMap에 등록됨</li>
 * </ol>
 *
 * @see LoanLimitStrategy
 */
@Slf4j
@Component
public class LoanLimitStrategyFactory {
    private final Map<LoanType, LoanLimitStrategy> strategyMap;

    /**
     * Spring이 수집한 {@link LoanLimitStrategy} 구현체 목록으로 전략 맵을 초기화한다.
     *
     * <p>동일한 {@code LoanType}을 반환하는 구현체가 2개 이상 존재하면
     * {@code Collectors.toMap()}에서 {@code IllegalStateException}이 발생한다.
     * 각 구현체에는 고유한 {@code LoanType}을 반환해야 한다.</p>
     *
     * @param strategies    Spring이 수집한 전체 Strategy 구현체 목록
     */
    public LoanLimitStrategyFactory(List<LoanLimitStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(LoanLimitStrategy::getLoanType, Function.identity()));

        log.info("LoanLimitStrategyFactory 초기화 완료. 등록된 전략: {}", strategyMap.keySet());
    }

    /**
     * 대출 유형에 해당하는 {@link LoanLimitStrategy}를 반환한다.
     *
     * <p>요청된 {@code LoanType}에 대한 Strategy가 등록되어 있지 않으면
     * 예외를 발생시킨다. 서비스 기동 후 전략 목록은 변경되지 않으므로,
     * 예외 발생 시 해당 {@code LoanType}에 대한 구현체가 누락된것으로 판단.</p>
     *
     * @param loanType  조회할 대출유형
     * @throws InvalidRequestException 등록되지 않은 대출 유형인 경우
     * @return  해당 대출유형의 Strategy 구현체
     */
    public LoanLimitStrategy getStrategy(LoanType loanType) {
        LoanLimitStrategy strategy = strategyMap.get(loanType);

        if(Objects.isNull(strategy)) {
            log.error("지원하지 않는 대출 유형. loanType: {}", loanType);
            throw new InvalidRequestException("지원하지 않는 대출 유형입니다: " + loanType);
        }

        return strategy;
    }
}
