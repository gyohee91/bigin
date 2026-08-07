package com.ghyinc.finance.domain.notification.enums;

import com.ghyinc.finance.domain.user.entity.Member;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.function.Function;

@Getter
@RequiredArgsConstructor
public enum ChannelType {
    SMS(Member::getMobile),
    EMAIL(Member::getEmail),
    KAKAOTALK(Member::getMobile),
    PUSH(Member::getToken);

    private final Function<Member, String> recipientExtractor;

    public String extractRecipient(Member member) {
        return Optional.ofNullable(member)
                .map(recipientExtractor)
                .orElseThrow(() -> new IllegalStateException(
                        "[" + this.name() + "] 채널에 필요한 Member 정보가 없습니다. userId="
                        + (member != null ? member.getUserId() : null)));
    }
}
