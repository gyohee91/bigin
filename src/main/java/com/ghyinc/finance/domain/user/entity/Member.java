package com.ghyinc.finance.domain.user.entity;

import com.ghyinc.finance.domain.user.enums.MemberRole;
import com.ghyinc.finance.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Member extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @Comment("고객명")
    private String name;

    @Comment("휴대폰번호")
    private String mobile;

    @Comment("이메일주소")
    private String email;

    @Comment("App Push Token")
    private String token;

    @Comment("Password")
    private String password;

    @Enumerated(EnumType.STRING)
    @Comment("사용자 권한")
    @Builder.Default
    private MemberRole role = MemberRole.USER;
}
