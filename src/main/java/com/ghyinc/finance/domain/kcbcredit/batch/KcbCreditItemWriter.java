package com.ghyinc.finance.domain.kcbcredit.batch;

import com.ghyinc.finance.domain.kcbcredit.dto.KcbCreditRecord;
import com.ghyinc.finance.domain.kcbcredit.enums.KcbCreditType;
import com.ghyinc.finance.domain.notification.dto.NotificationBulkResponse;
import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.service.NotificationService;
import com.ghyinc.finance.domain.user.entity.Member;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class KcbCreditItemWriter implements ItemWriter<KcbCreditRecord> {
    private final MemberRepository memberRepository;
    private final NotificationService notificationService;

    // chunk 단위 처리 통계 (JobListener에서 취합)
    @Getter
    private long notifiedCount;
    @Getter
    private long skippedCount;

    @Override
    @Transactional
    public void write(Chunk<? extends KcbCreditRecord> chunk) {
        List<? extends KcbCreditRecord> records = chunk.getItems();

        List<String> ciList = records.stream()
                .map(KcbCreditRecord::getCi)
                .toList();

        // chunk 내 회원 정보 한 번에 조회 (N+1 방지)
        Map<String, Member> memberByCi = memberRepository.findByCiIn(ciList).stream()
                .collect(Collectors.toMap(Member::getCi, Function.identity()));

        List<NotificationSendRequest> requests = new ArrayList<>();
        long unmatched = 0;

        for(KcbCreditRecord record : records) {
            Member member = memberByCi.get(record.getCi());
            if (member == null) {
                log.warn("[신용변동] 매칭되는 회원 없음. ci={}", record.getCi());
                skippedCount++;
                continue;
            }

            requests.add(
                    NotificationSendRequest.builder()
                            .userId(member.getUserId())
                            .channelType(ChannelType.KAKAOTALK)
                            .sendType(SendType.IMMEDIATE)
                            .title("잔액 변동 안내")
                            .content(this.buildContent(record))
                            .build()
            );

        }

        NotificationBulkResponse result = notificationService.setBulk(requests);

        notifiedCount += result.notified();
        skippedCount += unmatched + result.skipped();
    }

    private String buildContent(KcbCreditRecord record) {
        KcbCreditType type = record.resolveKcbCreditType();
        BigDecimal diff = record.getAfterBalance().subtract(record.getBeforeBalance()).abs();
        String direction = type == KcbCreditType.BALANCE_INCREASE ? "증가" : "감소";

        return String.format("회원님의 대출 잔액이 %s원 %s했습니다. (현재 잔액: %s원)",
                diff.toPlainString(), direction, record.getAfterBalance().toPlainString());
    }
}
