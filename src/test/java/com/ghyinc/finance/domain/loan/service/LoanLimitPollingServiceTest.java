package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.dto.LoanLimitPollingResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelStore;
import com.ghyinc.finance.domain.loan.readmodel.InquirySnapShot;
import com.ghyinc.finance.domain.loan.readmodel.InquirySnapShotLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoanLimitPollingServiceTest {
    LoanLimitPollingService loanLimitPollingService;

    @Mock
    InquiryReadModelStore store;

    @Mock
    LoanLimitService loanLimitService;

    @Mock
    InquirySnapShotLoader snapShotLoader;

    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        loanLimitPollingService = new LoanLimitPollingService(store, loanLimitService, snapShotLoader, objectMapper);
    }

    private LoanLimitProductResultDto dto(String loReqtNo, Long amount, PartnerInquiryStatus status) {
        return new LoanLimitProductResultDto(loReqtNo, PartnerCode.KAKAO_BANK, "k1", status, null, amount, 5.5);
    }

    private InquiryReadModelStore.ReadResult result(InquiryReadModelStore.Type type, long version, int total, int success, long card, List<String> json) {
        return new InquiryReadModelStore.ReadResult(type, version, total, success, "IN_PROGRESS", card, json);
    }

    @Test
    @DisplayName("플래그 OFF이면 기존 DB 경로로 위임한다")
    void getInquiryResult_delegatesToDb_whenDisabled() {
        LoanLimitPollingResponse asIs = LoanLimitPollingResponse.builder().inquiryNo("LL1").build();
        when(store.isEnabled()).thenReturn(false);
        when(loanLimitService.getInquiryResult(eq("LL1"), any(Pageable.class))).thenReturn(asIs);

        assertThat(loanLimitPollingService.getInquiryResult("LL1", 0, 20, 5L)).isSameAs(asIs);
        verify(store, never()).read(any(), any(), anyInt(), anyInt());
        verifyNoInteractions(snapShotLoader);
    }

    @Test
    @DisplayName("Redis 히트면 DB 조회 없이 응답한다")
    void getInquiryResult_buildsResponseWithoutDb_onHit() throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(this.dto("LR1", 300L, PartnerInquiryStatus.SUCCESS));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, 9L, 3, 1, 3, List.of(json)));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.version()).isEqualTo(9L);
        assertThat(response.changed()).isTrue();
        assertThat(response.totalProductCount()).isEqualTo(3);
        assertThat(response.progressRate()).isEqualTo(33);
        assertThat(response.allResultReceived()).isFalse();
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.productResults()).hasSize(1);
        verifyNoInteractions(loanLimitService, snapShotLoader);
    }

    @Test
    @DisplayName("버전이 같으면 changed=false로 응답한다")
    void getInquiryResult_returnsUnchanged_whenVersionMatches() {
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", 9L, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.SAME, 9L, 0, 0, 0, List.of()));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, 9L);

        assertThat(response.changed()).isFalse();
        assertThat(response.version()).isEqualTo(9L);
        assertThat(response.productResults()).isNull();
    }

    @Test
    @DisplayName("Redis 장애면 DB로 fallback한다")
    void getInquiryResult_fallsBackToDb_whenRedisDown() {
        LoanLimitPollingResponse asIs = LoanLimitPollingResponse.builder().inquiryNo("LL1").build();
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20)).thenReturn(this.result(InquiryReadModelStore.Type.DOWN, 0, 0, 0, 0, List.of()));
        when(loanLimitService.getInquiryResult(eq("LL1"), any(Pageable.class))).thenReturn(asIs);

        assertThat(loanLimitPollingService.getInquiryResult("LL1", 0, 20, null)).isSameAs(asIs);
        verify(store).count("fallback");
    }

    @Test
    @DisplayName("미스면 DB 스냅샷을 적재하고 다시 읽는다")
    void getInquiryResult_seedsAndRereads_onMiss() throws JsonProcessingException {
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "IN_PROGRESS", List.of());
        String json = objectMapper.writeValueAsString(this.dto("LR1", 300L, PartnerInquiryStatus.SUCCESS));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", 3L, 0, 20)).thenReturn(
                this.result(InquiryReadModelStore.Type.MISS, 0, 0, 0, 0, List.of()),
                this.result(InquiryReadModelStore.Type.OK, 100L, 1, 1, 1, List.of(json))
        );
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(store.seed(snapShot)).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, 3L);

        assertThat(response.version()).isEqualTo(100L);
        assertThat(response.allResultReceived()).isTrue();
        verify(store, times(2)).read("LL1", 3L, 0, 20);
    }

    @Test
    @DisplayName("적재가 실패하면 스냅샷으로 직접 응답하고 version은 없다")
    void getInquiryResult_responsesFromSnapShotWithoutVersion_whenSeedFails() {
        List<LoanLimitProductResultDto> items = List.of(
                this.dto("LR1", 300L, PartnerInquiryStatus.SUCCESS),
                this.dto("LR2", 200L, PartnerInquiryStatus.SEND_SUCCESS),
                this.dto("LR3", null, PartnerInquiryStatus.PENDING)
        );
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 3, "IN_PROGRESS", items);
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 2))
                .thenReturn(this.result(InquiryReadModelStore.Type.MISS, 0, 0, 0, 0, List.of()));
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(store.seed(snapShot)).thenReturn(false);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 2, null);

        assertThat(response.version()).isNull();
        assertThat(response.changed()).isNull();
        assertThat(response.productResults()).hasSize(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.successProductCount()).isEqualTo(1);
    }
}