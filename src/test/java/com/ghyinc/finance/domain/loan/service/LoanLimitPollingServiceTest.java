package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.config.InquiryPollingProperties;
import com.ghyinc.finance.domain.loan.config.StaleInquiryProperties;
import com.ghyinc.finance.domain.loan.dto.LoanLimitPollingResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelStore;
import com.ghyinc.finance.domain.loan.readmodel.InquirySnapShot;
import com.ghyinc.finance.domain.loan.readmodel.InquirySnapShotLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
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

    @Mock
    StaleInquiryFailService staleInquiryFailService;

    // record 는 mock 대신 실제 값 사용: clientWaitLimit 60s, pollInterval 2s / 타임아웃 2m,2m,5m
    InquiryPollingProperties pollingProperties = new InquiryPollingProperties(Duration.ofSeconds(60), Duration.ofSeconds(2));

    StaleInquiryProperties staleInquiryProperties =
            new StaleInquiryProperties(Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofMinutes(5), 100, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(20));

    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        loanLimitPollingService = new LoanLimitPollingService(store, loanLimitService, snapShotLoader, objectMapper, new SimpleMeterRegistry(), staleInquiryFailService, pollingProperties, staleInquiryProperties);
    }

    private LoanLimitProductResultDto dto(String loReqtNo, Long amount, PartnerInquiryStatus status) {
        return new LoanLimitProductResultDto(loReqtNo, PartnerCode.KAKAO_BANK, "k1", status, null, amount, 5.5);
    }

    private InquiryReadModelStore.ReadResult result(InquiryReadModelStore.Type type, long version, int total, int success, long card, List<String> json) {
        return new InquiryReadModelStore.ReadResult(type, version, total, success, "IN_PROGRESS", card, json);
    }

    private InquiryReadModelStore.ReadResult result(InquiryReadModelStore.Type type, String status, long deadlineAtMs) {
        return new InquiryReadModelStore.ReadResult(type, 9L, 3, 1, status, 3, List.of(), deadlineAtMs);
    }

    /** createdAt 이 지금으로부터 elapsed 전인 건의 deadline(epoch ms) = createdAt + clientWaitLimit(60s) */
    private long deadlineOfCreatedAgo(Duration elapsed) {
        return System.currentTimeMillis() - elapsed.toMillis() + pollingProperties.clientWaitLimit().toMillis();
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
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "IN_PROGRESS", List.of(), 0L);
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
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 3, "IN_PROGRESS", items, 0L);
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

    // ───────────────────────── 마감 판단(decorate) ─────────────────────────

    @Test
    @DisplayName("진행 중이고 마감 전이면 expired=false, remainingMs>0, 폴링 간격·마감 시각을 담는다")
    void decorate_inProgress_beforeDeadline_notExpired() {
        long deadline = deadlineOfCreatedAgo(Duration.ofSeconds(10));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "IN_PROGRESS", deadline));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isFalse();
        assertThat(response.remainingMs()).isBetween(1L, 50_000L);
        assertThat(response.deadlineAtEpochMs()).isEqualTo(deadline);
        assertThat(response.pollIntervalMs()).isEqualTo(2_000L);
        verifyNoInteractions(staleInquiryFailService);
    }

    @Test
    @DisplayName("진행 중인데 마감이 지났으면 expired=true, remainingMs=0 (아직 stale 기준 전이라 repair 는 하지 않는다)")
    void decorate_inProgress_pastDeadline_expired_withoutRepair() {
        long deadline = deadlineOfCreatedAgo(Duration.ofSeconds(90));   // 60s 마감 지남, 2분 타임아웃 전
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "IN_PROGRESS", deadline));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isTrue();
        assertThat(response.remainingMs()).isZero();
        verifyNoInteractions(staleInquiryFailService);
    }

    @Test
    @DisplayName("종료 상태(SUCCESS)는 마감이 지나도 expired=false, remainingMs=0")
    void decorate_terminal_pastDeadline_notExpired() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(10));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "SUCCESS", deadline));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isFalse();
        assertThat(response.remainingMs()).isZero();
        verifyNoInteractions(staleInquiryFailService);      // 종료 상태는 repair 대상이 아니다
    }

    @Test
    @DisplayName("unchanged(SAME) 응답에도 expired/remainingMs 가 붙는다 - 변화가 없어도 FE 가 종료를 알 수 있다")
    void decorate_same_pastDeadline_expiredOnUnchangedResponse() {
        long deadline = deadlineOfCreatedAgo(Duration.ofSeconds(90));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", 9L, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.SAME, "IN_PROGRESS", deadline));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, 9L);

        assertThat(response.changed()).isFalse();
        assertThat(response.expired()).isTrue();
        assertThat(response.remainingMs()).isZero();
    }

    @Test
    @DisplayName("deadline 을 모르면(0) 판단하지 않고 관련 필드를 비운다")
    void decorate_unknownDeadline_leavesFieldsNull() {
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "IN_PROGRESS", 0L));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isNull();
        assertThat(response.remainingMs()).isNull();
        assertThat(response.deadlineAtEpochMs()).isNull();
        verifyNoInteractions(staleInquiryFailService);
    }

    @Test
    @DisplayName("스냅샷 직접 응답 경로에도 마감 판단이 적용된다")
    void decorate_appliedOnSnapShotFallbackPath() {
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "IN_PROGRESS", List.of(),
                deadlineOfCreatedAgo(Duration.ofSeconds(90)));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.MISS, 0, 0, 0, 0, List.of()));
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(store.seed(snapShot)).thenReturn(false);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isTrue();
        assertThat(response.remainingMs()).isZero();
    }

    // ───────────────────────── read-repair ─────────────────────────

    @Test
    @DisplayName("repair - stale 기준(2분)을 넘겼고 CAS 가 성공하면 다시 읽어 FAILED 응답을 돌려준다")
    void repair_success_rereadsAndReturnsFailed() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20)).thenReturn(
                this.result(InquiryReadModelStore.Type.OK, "IN_PROGRESS", deadline),
                this.result(InquiryReadModelStore.Type.OK, "FAILED", deadline));
        when(staleInquiryFailService.repairIfStale("LL1")).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.inquiryStatus()).isEqualTo("FAILED");
        assertThat(response.expired()).isFalse();           // 종료 상태
        assertThat(response.remainingMs()).isZero();
        verify(staleInquiryFailService).repairIfStale("LL1");
        verify(store).count("read_repair");
        verify(store, times(2)).read("LL1", null, 0, 20);
    }

    @Test
    @DisplayName("repair - CAS 가 0건(정상 진행 중이거나 다른 경로가 처리)이면 원래 결과를 그대로 쓰고 재조회하지 않는다")
    void repair_notUpdated_keepsOriginalResult() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "IN_PROGRESS", deadline));
        when(staleInquiryFailService.repairIfStale("LL1")).thenReturn(false);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.inquiryStatus()).isEqualTo("IN_PROGRESS");
        assertThat(response.expired()).isTrue();
        verify(store, times(1)).read("LL1", null, 0, 20);
        verify(store, never()).count("read_repair");
    }

    @Test
    @DisplayName("repair - 예외가 나도 폴링 응답은 정상으로 나가고(배치가 backstop) 재조회하지 않는다")
    void repair_throws_swallowedAndOriginalResultReturned() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "IN_PROGRESS", deadline));
        when(staleInquiryFailService.repairIfStale("LL1")).thenThrow(new IllegalStateException("db down"));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.inquiryStatus()).isEqualTo("IN_PROGRESS");
        assertThat(response.expired()).isTrue();
        verify(store, times(1)).read("LL1", null, 0, 20);
    }

    @Test
    @DisplayName("repair - 성공 뒤 재조회가 MISS/DOWN 이면 원래 결과를 쓴다")
    void repair_success_butRereadMiss_keepsOriginalResult() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20)).thenReturn(
                this.result(InquiryReadModelStore.Type.OK, "IN_PROGRESS", deadline),
                this.result(InquiryReadModelStore.Type.MISS, 0, 0, 0, 0, List.of()));
        when(staleInquiryFailService.repairIfStale("LL1")).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.inquiryStatus()).isEqualTo("IN_PROGRESS");
        assertThat(response.expired()).isTrue();
    }

    @Test
    @DisplayName("repair - SAME 응답에서도 시도하고, 성공하면 새 버전 응답으로 바뀐다")
    void repair_onSame_replacedByNewVersion() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", 9L, 0, 20)).thenReturn(
                this.result(InquiryReadModelStore.Type.SAME, "IN_PROGRESS", deadline),
                this.result(InquiryReadModelStore.Type.OK, "FAILED", deadline));
        when(staleInquiryFailService.repairIfStale("LL1")).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, 9L);

        assertThat(response.changed()).isTrue();
        assertThat(response.inquiryStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("repair - 생성 후 stale 최소 타임아웃(2분) 전이면 DB 를 건드리지 않는다")
    void repair_beforeMinTimeout_skipsDb() {
        long deadline = deadlineOfCreatedAgo(Duration.ofSeconds(100));   // 2분 미만
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "IN_PROGRESS", deadline));

        loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        verifyNoInteractions(staleInquiryFailService);
    }

    @Test
    @DisplayName("repair - 상태를 알 수 없으면(빈 문자열) 종료 상태로 보지 않고 stale 기준이 지났으면 시도한다")
    void repair_unknownStatus_treatedAsNonTerminal() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "", deadline));
        when(staleInquiryFailService.repairIfStale("LL1")).thenReturn(false);

        loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        verify(staleInquiryFailService).repairIfStale("LL1");
    }

    // ───────────────────────── 계약: DB 경로와 같은 필드 ─────────────────────────

    @Test
    @DisplayName("Redis 히트 응답은 status 와 inquiryStatus 가 같다")
    void hit_hasSameStatusAndInquiryStatus() {
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "IN_PROGRESS", 0L));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.status()).isEqualTo("IN_PROGRESS");
        assertThat(response.inquiryStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("Redis status 가 빈 문자열이면 두 필드 모두 null (응답에서 생략)")
    void hit_blankStatus_isNull() {
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, "", 0L));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.status()).isNull();
        assertThat(response.inquiryStatus()).isNull();
    }

    @Test
    @DisplayName("스냅샷 직접 응답도 status 와 inquiryStatus 가 같다")
    void snapShotFallback_hasSameStatusAndInquiryStatus() {
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "SUCCESS", List.of(), 0L);
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.MISS, 0, 0, 0, 0, List.of()));
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(store.seed(snapShot)).thenReturn(false);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.inquiryStatus()).isEqualTo("SUCCESS");
    }

    // ───────────────────────── 파싱 실패 -> MISS ─────────────────────────

    private static final String BROKEN_JSON = "{\"loReqtNo\":\"LR1\",\"status\":\"NO_SUCH_STATUS\"}";   // enum 불일치

    @Test
    @DisplayName("저장된 JSON 을 파싱할 수 없으면 500 대신 MISS 로 취급해 DB 에서 다시 만들고 응답한다")
    void parseFailure_treatedAsMiss_andRebuilt() throws JsonProcessingException {
        String valid = objectMapper.writeValueAsString(this.dto("LR1", 300L, PartnerInquiryStatus.SUCCESS));
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "IN_PROGRESS", List.of(), 0L);
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20)).thenReturn(
                this.result(InquiryReadModelStore.Type.OK, 9L, 1, 0, 1, List.of(BROKEN_JSON)),
                this.result(InquiryReadModelStore.Type.OK, 100L, 1, 1, 1, List.of(valid)));
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(store.seed(snapShot)).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.version()).isEqualTo(100L);
        assertThat(response.productResults()).hasSize(1);
        verify(store).count("parse_fail");
        verify(store, times(2)).read("LL1", null, 0, 20);
    }

    @Test
    @DisplayName("재적재 후에도 파싱이 안 되면 스냅샷으로 직접 응답한다 (무한 반복 없음)")
    void parseFailure_persists_fallsBackToSnapShot() {
        List<LoanLimitProductResultDto> items = List.of(this.dto("LR1", 300L, PartnerInquiryStatus.SUCCESS));
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "SUCCESS", items, 0L);
        when(store.isEnabled()).thenReturn(true);
        when(store.read("LL1", null, 0, 20))
                .thenReturn(this.result(InquiryReadModelStore.Type.OK, 9L, 1, 0, 1, List.of(BROKEN_JSON)));
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(store.seed(snapShot)).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.version()).isNull();
        assertThat(response.productResults()).hasSize(1);
        assertThat(response.allResultReceived()).isTrue();
        verify(store, times(2)).count("parse_fail");
        verify(store).count("fallback");
        verify(store, times(2)).read("LL1", null, 0, 20);
    }
}
