package com.ghyinc.finance.global.outbox.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class OutboxCreatedEvent {
    private Long id;
}
