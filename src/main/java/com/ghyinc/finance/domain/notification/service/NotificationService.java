package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.NotificationBulkResponse;
import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.dto.NotificationSendResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.event.NotificationEvent;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import com.ghyinc.finance.domain.user.entity.Member;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import com.ghyinc.finance.global.outbox.service.OutboxEventWriter;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final MemberRepository memberRepository;

    private final OutboxEventWriter outboxEventWriter;

    @Transactional
    public NotificationSendResponse sendNotification(NotificationSendRequest request) {
        Member member = memberRepository.findById(request.getUserId())
                .orElseThrow(() -> new EntityNotFoundException("Member not found. id=" + request.getUserId()));

        Notification notification = Notification.builder()
                .channelType(request.getChannelType())
                .sendType(request.getSendType())
                .recipient(request.getChannelType().extractRecipient(member))
                .scheduledAt(request.getSendType() == SendType.SCHEDULED ? LocalDateTime.now() : null)
                .title(request.getTitle())
                .content(request.getContent())
                .build();

        notificationRepository.save(notification);

        //notificationEventProducer.publish(notification);
        //notificationSenderService.call(notification);

        // Spring 이벤트 발행 -> AFTER_COMMIT 후 Kafka 발행
        outboxEventWriter.enqueue(
                "Notification",
                String.valueOf(notification.getId()),
                "NOTIFICATION_SEND",
                NotificationEvent.from(notification)
        );

        //applicationEventPublisher.publishEvent(new OutboxCreatedEvent(outboxEvent.getId()));

        return NotificationSendResponse.from(notification);
    }

    @Transactional
    public NotificationBulkResponse setBulk(List<NotificationSendRequest> requests) {
        if (requests.isEmpty()) {
            return NotificationBulkResponse.builder()
                    .notified(0)
                    .skipped(0)
                    .build();
        }

        List<Long> userIds = requests.stream()
                .map(NotificationSendRequest::getUserId)
                .toList();

        Map<Long, Member> memberById = memberRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(Member::getUserId, Function.identity()));

        int notified = 0;
        int skipped = 0;

        for(NotificationSendRequest request : requests) {
            Member member = memberById.get(request.getUserId());
            if (member == null) {
                log.warn("[Bulk] Member 없음, 스킵. userId={}", request.getUserId());
                skipped++;
                continue;
            }

            Notification notification = Notification.builder()
                    .channelType(request.getChannelType())
                    .sendType(request.getSendType())
                    .recipient(request.getChannelType().extractRecipient(member))
                    .scheduledAt(request.getSendType() == SendType.SCHEDULED ? LocalDateTime.now() : null)
                    .title(request.getTitle())
                    .content(request.getContent())
                    .build();

            notificationRepository.save(notification);

            // Spring 이벤트 발행 -> AFTER_COMMIT 후 Kafka 발행
            outboxEventWriter.enqueue(
                    "Notification",
                    String.valueOf(notification.getId()),
                    "NOTIFICATION_SEND",
                    NotificationEvent.from(notification)
            );

            notified++;
        }

        return NotificationBulkResponse.from(notified, skipped);
    }

}
