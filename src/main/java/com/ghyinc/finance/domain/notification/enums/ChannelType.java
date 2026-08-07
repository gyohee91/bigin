package com.ghyinc.finance.domain.notification.enums;

import com.ghyinc.finance.domain.user.entity.Member;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

@Getter
@RequiredArgsConstructor
public enum ChannelType {
    SMS(Member::getMobile, null),
    EMAIL(Member::getEmail, null),
    KAKAOTALK(Member::getMobile, SMS),
    PUSH(Member::getToken, SMS);

    private final Function<Member, String> recipientExtractor;
    private final ChannelType fallback;

    /**
     * Member 정보를 바탕으로 실제 사용 가능한 ChannelType 결정
     * 현재 채널의 recipient 값이 없으면 fallback 채널로 재귀적 위임.
     */
    public ChannelType resolveAvailableChannel(Member member) {
        String value = recipientExtractor.apply(member);
        if (StringUtils.hasText(value)) {
            return this;
        }
        if(Objects.isNull(fallback)) {
            throw new IllegalStateException(
                    "[" + this.name() + "] 채널에 필요한 Member 정보가 없고, fallback 채널도 없습니다. userId=" + member.getUserId());
        }
        return fallback.resolveAvailableChannel(member);    // fallback도 값이 같이 없을 경우 대비해 재귀 처리
    }

    public String extractRecipient(Member member) {
        return Optional.ofNullable(recipientExtractor.apply(member))
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new IllegalStateException(
                        "[" + this.name() + "] 채널에 필요한 Member 정보가 없습니다. userId=" + member.getUserId()));
    }
}
