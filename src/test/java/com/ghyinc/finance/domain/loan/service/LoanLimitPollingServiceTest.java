package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.config.InquiryPollingProperties;
import com.ghyinc.finance.domain.loan.dto.LoanLimitPollingResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.*;
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
    PollingReadModel readModel;

    @Mock
    LoanLimitService loanLimitService;

    @Mock
    InquirySnapShotLoader snapShotLoader;

    @Mock
    StaleInquiryRepair staleInquiryRepair;

    // record 는 mock 대신 실제 값 사용: clientWaitLimit 60s, pollInterval 2s / 타임아웃 2m,2m,5m
    InquiryPollingProperties pollingProperties = new InquiryPollingProperties(Duration.ofSeconds(60), Duration.ofSeconds(2));

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    PollingMetrics metrics = new PollingMetrics(meterRegistry);

    @BeforeEach
    void setUp() {
        lenient().when(readModel.isEnabled()).thenReturn(true);
        loanLimitPollingService = new LoanLimitPollingService(
                readModel, loanLimitService, snapShotLoader, metrics,
                staleInquiryRepair, pollingProperties);
    }

    private LoanLimitProductResultDto dto(String loReqtNo, Long amount, PartnerInquiryStatus status) {
        return new LoanLimitProductResultDto(loReqtNo, PartnerCode.KAKAO_BANK, "k1", status, null, amount, 5.5);
    }

    private PollingView.Hit hit(long version, int total, int success, long totalElements, String status,
                                long deadlineAtMs, LoanLimitProductResultDto... items) {
        return new PollingView.Hit(version, total, success, status, totalElements, List.of(items), deadlineAtMs);
    }

    private PollingView.Hit hit(String status, long deadlineAtMs) {
        return this.hit(9L, 3, 1, 3, status, deadlineAtMs);
    }

    private PollingView.Unchanged same(String status, long deadlineAtMs) {
        return new PollingView.Unchanged(9L, status, deadlineAtMs);
    }

    /** createdAt 이 지금으로부터 elapsed 전인 건의 deadline(epoch ms) = createdAt + clientWaitLimit(60s) */
    private long deadlineOfCreatedAgo(Duration elapsed) {
        return System.currentTimeMillis() - elapsed.toMillis() + pollingProperties.clientWaitLimit().toMillis();
    }

    /**
     * 해당 path로 기록된 polling.service 호출 횟수
     */
    private long timeCount(String path) {
        return meterRegistry.get("polling.service")
                .tag("path", path)
                .timer()
                .count();
    }

    /**
     * polling.readmodel{result=...} 카운터 값
     */
    private double counter(String result) {
        return meterRegistry.get("polling.readmodel")
                .tag("result", result)
                .counter()
                .count();
    }

    @Test
    @DisplayName("플래그 OFF이면 기존 DB 경로로 위임한다")
    void getInquiryResult_delegatesToDb_whenDisabled() {
        LoanLimitPollingResponse asIs = LoanLimitPollingResponse.builder().inquiryNo("LL1").build();
        when(readModel.isEnabled()).thenReturn(false);
        when(loanLimitService.getInquiryResult(eq("LL1"), any(Pageable.class))).thenReturn(asIs);

        assertThat(loanLimitPollingService.getInquiryResult("LL1", 0, 20, 5L)).isSameAs(asIs);
        verify(readModel, never()).read(any(), any(), anyInt(), anyInt());
        verifyNoInteractions(snapShotLoader);
        assertThat(this.timeCount("db")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Redis 히트면 DB 조회 없이 응답한다")
    void getInquiryResult_buildsResponseWithoutDb_onHit() {
        when(readModel.read("LL1", null, 0, 20))
                .thenReturn(this.hit(9L, 3, 1, 3, "IN_PROGRESS", 0L, this.dto("LL1", 300L, PartnerInquiryStatus.SUCCESS)));

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
        assertThat(this.timeCount("redis")).isEqualTo(1L);
    }

    @Test
    @DisplayName("버전이 같으면 changed=false로 응답한다")
    void getInquiryResult_returnsUnchanged_whenVersionMatches() {
        when(readModel.read("LL1", 9L, 0, 20))
                .thenReturn(this.same("IN_PROGRESS", 0L));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, 9L);

        assertThat(response.changed()).isFalse();
        assertThat(response.version()).isEqualTo(9L);
        assertThat(response.productResults()).isNull();
        assertThat(this.timeCount("unchanged")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Redis 장애면 DB로 fallback한다")
    void getInquiryResult_fallsBackToDb_whenRedisDown() {
        LoanLimitPollingResponse asIs = LoanLimitPollingResponse.builder().inquiryNo("LL1").build();
        when(readModel.read("LL1", null, 0, 20)).thenReturn(PollingView.Down.INSTANCE);
        when(loanLimitService.getInquiryResult(eq("LL1"), any(Pageable.class))).thenReturn(asIs);

        assertThat(loanLimitPollingService.getInquiryResult("LL1", 0, 20, null)).isSameAs(asIs);
        assertThat(this.counter("fallback")).isEqualTo(1.0);
        assertThat(this.timeCount("fallback_down")).isEqualTo(1L);
    }

    @Test
    @DisplayName("미스면 DB 스냅샷을 적재하고 다시 읽는다")
    void getInquiryResult_seedsAndRereads_onMiss() {
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "IN_PROGRESS", List.of(), 0L);
        when(readModel.read("LL1", 3L, 0, 20)).thenReturn(
                PollingView.Miss.INSTANCE,
                this.hit(100L, 1, 1, 1, "IN_PROGRESS", 0L, this.dto("LL1", 300L, PartnerInquiryStatus.SUCCESS))
        );
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(readModel.seed(snapShot)).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, 3L);

        assertThat(response.version()).isEqualTo(100L);
        assertThat(response.allResultReceived()).isTrue();
        verify(readModel, times(2)).read("LL1", 3L, 0, 20);
        assertThat(this.timeCount("rebuild")).isEqualTo(1L);
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
        when(readModel.read("LL1", null, 0, 2)).thenReturn(PollingView.Miss.INSTANCE);
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(readModel.seed(snapShot)).thenReturn(false);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 2, null);

        assertThat(response.version()).isNull();
        assertThat(response.changed()).isNull();
        assertThat(response.productResults()).hasSize(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.successProductCount()).isEqualTo(1);
        assertThat(this.counter("fallback")).isEqualTo(1.0);
        assertThat(this.timeCount("fallback_seed")).isEqualTo(1L);
    }

    // ───────────────────────── 마감 판단(decorate) ─────────────────────────

    @Test
    @DisplayName("진행 중이고 마감 전이면 expired=false, remainingMs>0, 폴링 간격·마감 시각을 담는다")
    void decorate_inProgress_beforeDeadline_notExpired() {
        long deadline = deadlineOfCreatedAgo(Duration.ofSeconds(10));
        when(readModel.read("LL1", null, 0, 20))
                .thenReturn(this.hit("IN_PROGRESS", deadline));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isFalse();
        assertThat(response.remainingMs()).isBetween(1L, 50_000L);
        assertThat(response.deadlineAtEpochMs()).isEqualTo(deadline);
        assertThat(response.pollIntervalMs()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("진행 중인데 마감이 지났으면 expired=true, remainingMs=0 (아직 stale 기준 전이라 repair 는 하지 않는다)")
    void decorate_inProgress_pastDeadline_expired_withoutRepair() {
        long deadline = deadlineOfCreatedAgo(Duration.ofSeconds(90));   // 60s 마감 지남, 2분 타임아웃 전
        when(readModel.read("LL1", null, 0, 20))
                .thenReturn(this.hit("IN_PROGRESS", deadline));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isTrue();
        assertThat(response.remainingMs()).isZero();
    }

    @Test
    @DisplayName("종료 상태(SUCCESS)는 마감이 지나도 expired=false, remainingMs=0")
    void decorate_terminal_pastDeadline_notExpired() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(10));
        when(readModel.read("LL1", null, 0, 20))
                .thenReturn(this.hit("SUCCESS", deadline));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isFalse();
        assertThat(response.remainingMs()).isZero();
    }

    @Test
    @DisplayName("unchanged(SAME) 응답에도 expired/remainingMs 가 붙는다 - 변화가 없어도 FE 가 종료를 알 수 있다")
    void decorate_same_pastDeadline_expiredOnUnchangedResponse() {
        long deadline = deadlineOfCreatedAgo(Duration.ofSeconds(90));
        when(readModel.read("LL1", 9L, 0, 20))
                .thenReturn(this.same("IN_PROGRESS", deadline));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, 9L);

        assertThat(response.changed()).isFalse();
        assertThat(response.expired()).isTrue();
        assertThat(response.remainingMs()).isZero();
    }

    @Test
    @DisplayName("deadline 을 모르면(0) 판단하지 않고 관련 필드를 비운다")
    void decorate_unknownDeadline_leavesFieldsNull() {
        when(readModel.read("LL1", null, 0, 20))
                .thenReturn(this.hit("IN_PROGRESS", 0L));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isNull();
        assertThat(response.remainingMs()).isNull();
        assertThat(response.deadlineAtEpochMs()).isNull();
    }

    @Test
    @DisplayName("스냅샷 직접 응답 경로에도 마감 판단이 적용된다")
    void decorate_appliedOnSnapShotFallbackPath() {
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "IN_PROGRESS", List.of(),
                deadlineOfCreatedAgo(Duration.ofSeconds(90)));
        when(readModel.read("LL1", null, 0, 20)).thenReturn(PollingView.Miss.INSTANCE);
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(readModel.seed(snapShot)).thenReturn(false);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.expired()).isTrue();
        assertThat(response.remainingMs()).isZero();
    }

    // ───────────────────────── read-repair (판단 로직은 StaleInquiryRepairTest) ─────────────────────────

    @Test
    @DisplayName("repair - Repair 가 true 이면 다시 읽어 FAILED 응답을 돌려준다")
    void repair_success_rereadsAndReturnsFailed() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(readModel.read("LL1", null, 0, 20)).thenReturn(
                this.hit("IN_PROGRESS", deadline),
                this.hit("FAILED", deadline));
        when(staleInquiryRepair.repairIfDue("LL1", InquiryStatus.IN_PROGRESS, deadline)).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.inquiryStatus()).isEqualTo("FAILED");
        assertThat(response.expired()).isFalse();           // 종료 상태
        assertThat(response.remainingMs()).isZero();
        assertThat(this.counter("read_repair")).isEqualTo(1.0);
        verify(readModel, times(2)).read("LL1", null, 0, 20);
    }

    @Test
    @DisplayName("repair - Repair가 false 이면 원래 결과를 그대로 쓰고 재조회하지 않는다")
    void repair_notUpdated_keepsOriginalResult() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(readModel.read("LL1", null, 0, 20))
                .thenReturn(this.hit("IN_PROGRESS", deadline));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.inquiryStatus()).isEqualTo("IN_PROGRESS");
        assertThat(response.expired()).isTrue();
        verify(readModel, times(1)).read("LL1", null, 0, 20);
        assertThat(this.counter("read_repair")).isZero();
    }

    @Test
    @DisplayName("repair - 성공 뒤 재조회가 MISS/DOWN 이면 원래 결과를 쓴다")
    void repair_throws_swallowedAndOriginalResultReturned() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(readModel.read("LL1", null, 0, 20)).thenReturn(
                this.hit("IN_PROGRESS", deadline),
                PollingView.Miss.INSTANCE);
        when(staleInquiryRepair.repairIfDue("LL1", InquiryStatus.IN_PROGRESS, deadline)).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.inquiryStatus()).isEqualTo("IN_PROGRESS");
        assertThat(response.expired()).isTrue();
    }

    @Test
    @DisplayName("repair - SAME 응답에서도 시도하고, 성공하면 새 버전 응답으로 바뀐다")
    void repair_onSame_replacedByNewVersion() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(readModel.read("LL1", 9L, 0, 20)).thenReturn(
                this.same("IN_PROGRESS", deadline),
                this.hit("FAILED", deadline));
        when(staleInquiryRepair.repairIfDue("LL1", InquiryStatus.IN_PROGRESS, deadline)).thenReturn(true);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, 9L);

        assertThat(response.changed()).isTrue();
        assertThat(response.inquiryStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("repair - 상태가 빈 문자열이면 null 상태로 넘긴다 (종료 여부 판단은 Repairer)")
    void repair_unknownStatus_treatedAsNonTerminal() {
        long deadline = deadlineOfCreatedAgo(Duration.ofMinutes(3));
        when(readModel.read("LL1", null, 0, 20)).thenReturn(this.hit("", deadline));

        loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        verify(staleInquiryRepair).repairIfDue("LL1", null, deadline);
    }

    // ───────────────────────── 계약: DB 경로와 같은 필드 ─────────────────────────

    @Test
    @DisplayName("Redis 히트 응답은 status 와 inquiryStatus 가 같다")
    void hit_hasSameStatusAndInquiryStatus() {
        when(readModel.read("LL1", null, 0, 20))
                .thenReturn(this.hit("IN_PROGRESS", 0L));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.status()).isEqualTo("IN_PROGRESS");
        assertThat(response.inquiryStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("Redis status 가 빈 문자열이면 두 필드 모두 null (응답에서 생략)")
    void hit_blankStatus_isNull() {
        when(readModel.read("LL1", null, 0, 20))
                .thenReturn(this.hit("", 0L));

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.status()).isNull();
        assertThat(response.inquiryStatus()).isNull();
    }

    @Test
    @DisplayName("스냅샷 직접 응답도 status 와 inquiryStatus 가 같다")
    void snapShotFallback_hasSameStatusAndInquiryStatus() {
        InquirySnapShot snapShot = new InquirySnapShot("LL1", 1, "SUCCESS", List.of(), 0L);
        when(readModel.read("LL1", null, 0, 20)).thenReturn(PollingView.Miss.INSTANCE);
        when(snapShotLoader.load("LL1")).thenReturn(snapShot);
        when(readModel.seed(snapShot)).thenReturn(false);

        LoanLimitPollingResponse response = loanLimitPollingService.getInquiryResult("LL1", 0, 20, null);

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.inquiryStatus()).isEqualTo("SUCCESS");
    }
}
