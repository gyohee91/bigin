package com.ghyinc.finance.domain.loan.entity;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerType;
import com.ghyinc.finance.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Partner extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Comment("제휴사 코드")
    private PartnerCode partnerCode;

    @Column(nullable = false)
    @Comment("제휴사명")
    private String partnerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Comment("제휴사 타입")
    private PartnerType partnerType;

    @Column(nullable = false)
    @Comment("활성화 여부")
    private boolean active;
}
