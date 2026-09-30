package com.ghyinc.finance.domain.errormonitor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "error-monitor")
public class ErrorMonitorProperties {
    private boolean enabled = true;
    private int windowSeconds = 60;
    private long defaultThreshold = 10;
    private int cooldownSeconds = 300;
    private int fallbackQueryIntervalSeconds = 10;
    private int messageMaxLength = 500;
    private Map<String, Long> thresholds = new HashMap<>();
    private Dooray dooray = new Dooray();

    @Getter
    @Setter
    public static class Dooray {
        private boolean enabled = false;
        private String webhookUrl;
    }
}
