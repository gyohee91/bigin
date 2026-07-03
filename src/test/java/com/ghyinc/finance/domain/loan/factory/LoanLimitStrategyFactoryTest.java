package com.ghyinc.finance.domain.loan.factory;

import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;


@ExtendWith(MockitoExtension.class)
class LoanLimitStrategyFactoryTest {

    private LoanLimitStrategyFactory strategyFactory;

    private LoanLimitStrategy personalCreditStrategy;
    private LoanLimitStrategy autoStrategy;

    @BeforeEach
    void setUp() {
        personalCreditStrategy = mock(LoanLimitStrategy.class);
        given(personalCreditStrategy.getLoanType()).willReturn(LoanType.PERSONAL_CREDIT);

        autoStrategy = mock(LoanLimitStrategy.class);
        given(autoStrategy.getLoanType()).willReturn(LoanType.AUTO);

        strategyFactory = new LoanLimitStrategyFactory(
                List.of(personalCreditStrategy, autoStrategy)
        );
    }

    @Test
    @DisplayName("지원하지 않는 LoanType 요청 시 예외 발생")
    void getStrategy_unsupportedLoanType_throwsException() {
        // when & then
        assertThatThrownBy(() -> strategyFactory.getStrategy(LoanType.BUSINESS))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("지원하지 않는 대출 유형입니다: " + LoanType.BUSINESS);
    }

    @Test
    @DisplayName("지원하는 LoanType 요청 시 Strategy 반환")
    void getStrategy_validLoanType_returnStrategy() {
        // when
        LoanLimitStrategy result = strategyFactory.getStrategy(LoanType.PERSONAL_CREDIT);

        // then
        assertThat(result).isEqualTo(personalCreditStrategy);
    }

    @Test
    @DisplayName("등록된 모든 Strategy LoanType 정상 조회")
    void getStrategy_allRegisteredTypes_returnsCorrectStrategy() {
        assertThat(strategyFactory.getStrategy(LoanType.PERSONAL_CREDIT))
                .isEqualTo(personalCreditStrategy);
        assertThat(strategyFactory.getStrategy(LoanType.AUTO))
                .isEqualTo(autoStrategy);
    }
}