package com.ghyinc.finance.global.outbox.event;

/**
 * Spring 내부 이벤트
 * Spring의 ApplicationEvent를 수신
 *
 * @param id
 */
public record OutboxCreatedEvent(
        Long id
) {}
