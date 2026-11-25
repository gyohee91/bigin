package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LoanLimitCounterServiceTest {

    @InjectMocks
    private LoanLimitCounterService loanLimitCounterService;

    @Mock
    private LoanLimitProductResultRepository loanLimitProductResultRepository;

    @Test
    @DisplayName("repository의 원자적 UPDATE 결과를 그대로 위임/반환한다")
    void incrementSuccessCount_delegatesToRepository() {
        // given
        given(loanLimitProductResultRepository.incrementSuccessProductCount(eq("LR20260410AAA"), eq("P060100206"), any(LocalDateTime.class)))
                .willReturn(1);

        // when
        int updated = loanLimitCounterService.incrementSuccessCount("LR20260410AAA", "P060100206");

        // then
        assertThat(updated).isEqualTo(1);
        then(loanLimitProductResultRepository).should().incrementSuccessProductCount(eq("LR20260410AAA"), eq("P060100206"), any(LocalDateTime.class));
    }
}
