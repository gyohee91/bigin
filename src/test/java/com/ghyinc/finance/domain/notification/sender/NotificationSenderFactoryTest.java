package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.enums.ChannelType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class NotificationSenderFactoryTest {

    private NotificationSender mockSender(ChannelType channelType) {
        NotificationSender sender = mock(NotificationSender.class);
        given(sender.getChannelType()).willReturn(channelType);
        return sender;
    }

    @Test
    @DisplayName("등록된 채널 타입으로 조회하면 해당 NotificationSender를 반환한다")
    void getSender_returnsMatchingSender() {
        // given
        NotificationSender smsSender = this.mockSender(ChannelType.SMS);
        NotificationSender emailSender = this.mockSender(ChannelType.EMAIL);
        NotificationSenderFactory factory = new NotificationSenderFactory(List.of(smsSender, emailSender));

        // when
        NotificationSender result = factory.getSender(ChannelType.SMS);

        // then
        assertThat(result).isSameAs(smsSender);
    }

    @Test
    @DisplayName("등록되지 않은 채널 타입으로 조회하면 IllegalArgumentException을 던진다")
    void getSender_throwsIllegalArgumentException_whenChannelNotSupported() {
        // given
        NotificationSenderFactory factory = new NotificationSenderFactory(List.of(this.mockSender(ChannelType.SMS)));

        // when & then
        assertThatThrownBy(() -> factory.getSender(ChannelType.KAKAOTALK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KAKAOTALK");
    }
}