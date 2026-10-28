package com.ghyinc.finance.domain.loan.readmodel;

import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InquirySnapShotLoaderTest {
    @InjectMocks
    private InquirySnapShotLoader loader;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private LoanLimitProductResultRepository loanLimitProductResultRepository;

    @Test
    @DisplayName("존재하지 않는 inquiryNo이면 예외")
    void load_throwsWhenInquiryNotFound() {
        when(loanLimitInquiryRepository.findByInquiryNo("NONE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loader.load("NONE"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("전체 목록을 unpaged로 조회해 스냅샷을 만든다")
    void load_buildsSnapshotWithUnpagedQuery() {
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder().inquiryNo("LL1").build();
        ReflectionTestUtils.setField(inquiry, "id", 1L);
        inquiry.initProductCount(2);
        List<LoanLimitProductResultDto> items = List.of(
                new LoanLimitProductResultDto("LR1", PartnerCode.KAKAO_BANK, "p1",
                        PartnerInquiryStatus.SUCCESS, null, 300L, 4.0),
                new LoanLimitProductResultDto("LR2", PartnerCode.TOSS_BANK, "t1",
                        PartnerInquiryStatus.SUCCESS, null, null, 0.0)
        );
        when(loanLimitInquiryRepository.findByInquiryNo("LL1")).thenReturn(Optional.of(inquiry));
        when(loanLimitProductResultRepository.findProductResultsByInquiryId(1L, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(items));

        InquirySnapShot snapShot = loader.load("LL1");

        assertThat(snapShot.inquiryNo()).isEqualTo("LL1");
        assertThat(snapShot.total()).isEqualTo(2);
        assertThat(snapShot.status()).isEqualTo("PENDING");
        assertThat(snapShot.items()).hasSize(2);
    }
}