package com.ghyinc.finance.domain.kcbcredit.batch;

import com.ghyinc.finance.domain.kcbcredit.dto.KcbCreditRecord;
import com.ghyinc.finance.domain.notification.dto.NotificationBulkResponse;
import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.service.NotificationService;
import com.ghyinc.finance.domain.user.entity.Member;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class KcbCreditItemWriterTest {
    @InjectMocks
    private KcbCreditItemWriter kcbCreditItemWriter;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private NotificationService notificationService;

    private KcbCreditRecord record(String ci, String creditTypeCode, String before, String after) {
        KcbCreditRecord record = new KcbCreditRecord();
        record.setCi(ci);
        record.setKcbCreditTypeCode(creditTypeCode);
        record.setBeforeBalance(new BigDecimal(before));
        record.setAfterBalance(new BigDecimal(after));
        record.setChangedAt(LocalDate.of(2026, 9, 1));
        return record;
    }

    private Member member(Long userId, String ci) {
        return Member.builder()
                .userId(userId)
                .name("Tony")
                .mobile("01012341234")
                .ci(ci)
                .build();
    }

    @Test
    @DisplayName("CI가 매칭되는 레코드만 Request DTO로 변환해 sendBulk()를 호출한다")
    void write_buildRequestsOnlyForMatchedMembers() {
        // given
        KcbCreditRecord matched = this.record("CI-001", "01", "10000", "150000");
        KcbCreditRecord unmatched = this.record("CI-999", "01", "10000", "150000");
        Chunk<KcbCreditRecord> chunk = new Chunk<>(List.of(matched, unmatched));

        given(memberRepository.findByCiIn(List.of("CI-001", "CI-999")))
                .willReturn(List.of(this.member(10L, "CI-001")));
        given(notificationService.setBulk(any())).willReturn(NotificationBulkResponse.builder().notified(1).skipped(0).build());

        // when
        kcbCreditItemWriter.write(chunk);

        // then
        ArgumentCaptor<List<NotificationSendRequest>> captor = ArgumentCaptor.forClass(List.class);
        then(notificationService).should().setBulk(captor.capture());

        List<NotificationSendRequest> requests = captor.getValue();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getUserId()).isEqualTo(10L);
        assertThat(requests.get(0).getChannelType()).isEqualTo(ChannelType.KAKAOTALK);

        assertThat(kcbCreditItemWriter.getNotifiedCount()).isEqualTo(1);
        assertThat(kcbCreditItemWriter.getSkippedCount()).isEqualTo(1);     // CI 매칭 실패 1건
    }

    @Test
    @DisplayName("CI 매칭 실패 레코드만 있으면 sendBulk에 빈 리스트를 전달한다")
    void write_allUnmatched_callsSendBulkWithEmptyList() {
        // given
        KcbCreditRecord unmatched = this.record("CI-9999", "01", "10000", "150000");
        Chunk<KcbCreditRecord> chunk = new Chunk<>(List.of(unmatched));

        given(memberRepository.findByCiIn(List.of("CI-9999"))).willReturn(List.of());
        given(notificationService.setBulk(any())).willReturn(NotificationBulkResponse.builder().notified(0).skipped(0).build());

        // when
        kcbCreditItemWriter.write(chunk);

        // then
        then(notificationService).should().setBulk(List.of());
        assertThat(kcbCreditItemWriter.getNotifiedCount()).isEqualTo(0);
        assertThat(kcbCreditItemWriter.getSkippedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("sendBulk 결과의 notifiedCount/skippedCount가 그대로 누적된다")
    void write_accumulatesCountsFromSendBulkResult() {
        // given
        KcbCreditRecord matched = this.record("CI-0001", "01", "100000", "1500000");
        Chunk<KcbCreditRecord> chunk = new Chunk<>(List.of(matched));

        given(memberRepository.findByCiIn(List.of("CI-0001"))).willReturn(List.of(this.member(1L, "CI-0001")));
        // CI는 매칭됐지만 sendBulk 내부에서 발송 채널 없음으로 스킵된 케이스
        given(notificationService.setBulk(any())).willReturn(NotificationBulkResponse.builder().notified(0).skipped(1).build());

        // when
        kcbCreditItemWriter.write(chunk);

        // then
        assertThat(kcbCreditItemWriter.getNotifiedCount()).isEqualTo(0);
        assertThat(kcbCreditItemWriter.getSkippedCount()).isEqualTo(1);
    }
}