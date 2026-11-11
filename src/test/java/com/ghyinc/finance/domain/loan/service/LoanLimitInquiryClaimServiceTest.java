package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class LoanLimitInquiryClaimServiceTest {

    private static final String DEDUP_KEY = "1:PERSONAL_CREDIT:20260101";

    @InjectMocks
    private LoanLimitInquiryClaimService claimService;

    @Mock
    private LoReqtNoGenerator generator;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Test
    @DisplayName("claim - Redis 에 의존하지 않는 채번(generateGuid)으로 inquiryNo 를 만들고 ACCEPTED 행을 즉시 flush 하여 반환")
    void claim_savesAcceptedInquiryAndReturnsInquiryNo() {
        given(generator.generate("LL")).willReturn("LL20260101abcdef");

        String inquiryNo = claimService.claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY);

        assertThat(inquiryNo).isEqualTo("LL20260101abcdef");
        ArgumentCaptor<LoanLimitInquiry> captor = ArgumentCaptor.forClass(LoanLimitInquiry.class);
        // unique 위반을 이 메서드(REQUIRES_NEW 트랜잭션) 안에서 즉시 드러내야 하므로 save 가 아니라 saveAndFlush
        then(loanLimitInquiryRepository).should().saveAndFlush(captor.capture());
        LoanLimitInquiry saved = captor.getValue();
        assertThat(saved.getInquiryNo()).isEqualTo("LL20260101abcdef");
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getLoanType()).isEqualTo(LoanType.PERSONAL_CREDIT);
        assertThat(saved.getStatus()).isEqualTo(InquiryStatus.ACCEPTED);
        assertThat(saved.getDedupKey()).isEqualTo(DEDUP_KEY);
    }

    @Test
    @DisplayName("claim - 접수 시점에는 개인정보(이름·CI 등)를 저장하지 않는다")
    void claim_doesNotStorePersonalInformation() {
        given(generator.generate("LL")).willReturn("LL20260101abcdef");

        claimService.claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY);

        ArgumentCaptor<LoanLimitInquiry> captor = ArgumentCaptor.forClass(LoanLimitInquiry.class);
        then(loanLimitInquiryRepository).should().saveAndFlush(captor.capture());
        LoanLimitInquiry saved = captor.getValue();
        assertThat(saved.getName()).isNull();
        assertThat(saved.getCi()).isNull();
        assertThat(saved.getJobName()).isNull();
        assertThat(saved.getCarNo()).isNull();
    }

    @Test
    @DisplayName("claim - dedup_key unique 위반은 변환 없이 호출자에게 전파 (호출자가 중복 여부를 판정)")
    void claim_propagatesDataIntegrityViolation() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("uk_inquiry_dedup_key");
        given(generator.generate("LL")).willReturn("LL20260101abcdef");
        given(loanLimitInquiryRepository.saveAndFlush(any(LoanLimitInquiry.class))).willThrow(violation);

        assertThatThrownBy(() -> claimService.claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY))
                .isSameAs(violation);
    }

    @Test
    @DisplayName("abort - ACCEPTED 상태일 때만 FAILED 로 바꾸는 CAS 쿼리(failIfAccepted)에 위임")
    void abort_delegatesToFailIfAccepted() {
        claimService.abort("LL20260101abcdef");

        then(loanLimitInquiryRepository).should().failIfAccepted("LL20260101abcdef");
        then(loanLimitInquiryRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("claim/abort 는 호출자의 트랜잭션과 분리된 REQUIRES_NEW 여야 한다 (외부 조회 동안 DB 트랜잭션 점유 방지 + 즉시 커밋)")
    void claimAndAbort_areRequiresNewTransactions() throws NoSuchMethodException {
        Transactional claimTx = LoanLimitInquiryClaimService.class
                .getMethod("claim", Long.class, LoanType.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional abortTx = LoanLimitInquiryClaimService.class
                .getMethod("abort", String.class)
                .getAnnotation(Transactional.class);

        assertThat(claimTx).isNotNull();
        assertThat(claimTx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(abortTx).isNotNull();
        assertThat(abortTx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
