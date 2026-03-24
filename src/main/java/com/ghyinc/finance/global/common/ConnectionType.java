package com.ghyinc.finance.global.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ConnectionType {
    REST,       //REST API
    LEASE_LINE; //전용선
}
