package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.enums.ChannelType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class NotificationSenderFactory {
    private final Map<ChannelType, NotificationSender> senderMap;


    public NotificationSenderFactory(List<NotificationSender> senders) {
        this.senderMap = senders.stream()
                .collect(Collectors.toMap(
                        NotificationSender::getChannelType,
                        Function.identity()
                ));
    }

    public NotificationSender getSender(ChannelType channelType) {
        NotificationSender sender = senderMap.get(channelType);
        if(sender == null) {
            throw new IllegalArgumentException("지원하지 않는 채널입니다: " + channelType);
        }
        return sender;
    }
}
