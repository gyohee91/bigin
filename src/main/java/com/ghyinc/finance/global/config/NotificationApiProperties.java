package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.notification.enums.ChannelType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notification-api")
public class NotificationApiProperties {
    private Map<ChannelType, ChannelApiConfig> channels;

    public ChannelApiConfig getConfig(ChannelType channelType) {
        ChannelApiConfig config = channels.get(channelType);
        if(Objects.isNull(config)) {
            throw new IllegalStateException("알림 채널 설정이 없습니다: " + channelType);
        }
        return config;
    }

    @Getter
    @Setter
    public static class ChannelApiConfig {
        private String baseUrl;
        private String path;
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 5000;
    }
}
