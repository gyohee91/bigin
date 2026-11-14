package com.ghyinc.finance.domain.loan.entity;

import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        uniqueConstraints = @UniqueConstraint(name = "uk_inquiry_dedup_key", columnNames = "dedup_key"),
        indexes = {
                @Index(
                        name = "idx_inquiry_user_id_loan_type_status",
                        columnList = "user_id, loan_type, status"
                )
        }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LoanLimitInquiry extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    @Comment("업무 식별번호")
    private String inquiryNo;   // 외부 노출용 (FE 폴링, 콜백 연결, 이력 조회 KEY)

    @Column(nullable = false)
    @Comment("고객번호")
    private Long userId;

    @Comment("고객명")
    private String name;

    @Comment("CI")
    private String ci;

    @Enumerated(EnumType.STRING)
    @Comment("직업 구분")
    private JobType jobType;

    @Comment("직장명")
    private String jobName;

    @Comment("입사년월(개업년월)")
    private String joinDate;

    @Enumerated(EnumType.STRING)
    @Comment("대출 유형")
    private LoanType loanType;

    @Comment("차량번호")
    private String carNo;

    @Comment("신용정보 수집 이용 제공 동의 여부")
    private boolean agreePersonalCreditInfo;

    @Comment("신용정보 수집 이용 제공 동의 시간")
    private LocalDateTime agreePersonalCreditTime;

    @Enumerated(EnumType.STRING)
    @Comment("응답 결과")
    @Builder.Default
    private InquiryStatus status = InquiryStatus.PENDING;

    @Comment("전체 상품 갯수")
    private int totalProductCount;

    @Comment("Success 상품 갯수")
    private int successProductCount;

    @OneToMany(mappedBy = "loanLimitInquiry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LoanLimitResult> results = new ArrayList<>();

    @OneToMany(mappedBy = "loanLimitInquiry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LoanLimitProductResult> productResults = new ArrayList<>();

    @Comment("당일 중복 조회 방지 키. FAILED 시 null 로 해제")
    private String dedupKey;    // userId:loanType:yyyyMMdd. unique(null 허용). 성공 건은 유지(하루 1회), FAILED 는 null → 재조회 허용

    /**
     * 상태를 변경한다. FAILED 로 전이하면 {@code dedupKey} 를 비워 슬롯을 해제(당일 재조회 허용)한다.
     * SUCCESS / PARTIAL_SUCCESS 는 값을 유지하므로 하루 1회 제한이 계속 적용된다.
     */
    public void updateInquiryStatus(InquiryStatus status) {
        this.status = status;
        if (status == InquiryStatus.FAILED) {
            this.dedupKey = null;   // 슬롯 해제: 당일 재시도 허용
        }
    }

    public void addResult(LoanLimitResult result) {
        this.results.add(result);
        result.assignInquiry(this);
    }

    public void addProductResult(LoanLimitProductResult productResult) {
        this.productResults.add(productResult);
        productResult.assignInquiry(this);
    }

    public void initProductCount(int total) {
        this.totalProductCount = total;
        this.successProductCount = 0;
    }

    public void incrementSuccessCount() {
        this.successProductCount++;
    }

    /**
     * 한도결과 수신 진행률 (0 ~ 100)
     */
    public int getProgressRate() {
        if (totalProductCount == 0)
            return 0;
        return (int) ((successProductCount * 100.0) / totalProductCount);
    }

    public boolean isAllResultReceived() {
        return totalProductCount > 0 && successProductCount >= totalProductCount;
    }

    /**
     * 요청의 고객·직업·차량·동의 정보를 반영한다. DB 선점(ACCEPTED) 시점에는 최소 행만 만들기 때문에
     * 완료 단계에서 채운다. 식별번호·상태·dedupKey 는 건드리지 않는다.
     */
    public void applyRequest(LoanLimitRequest request) {
        this.name = request.name();
        this.ci = request.ci();
        this.jobType = request.jobType();
        this.jobName = request.jobName();
        this.joinDate = request.joinDate();
        this.carNo = request.carNo();
        this.agreePersonalCreditInfo = request.agreePersonalCreditInfo();
        this.agreePersonalCreditTime = request.agreePersonalCreditTime();
    }

    /**
     * 당일 중복 방지 키 생성: {@code userId:loanType:yyyyMMdd}.
     * 날짜는 호출자가 정한 기준일이다. (서버 기본 시간대 기준이므로 운영 시간대에 유의)
     */
    public static String dedupKeyOf(Long userId, LoanType loanType, LocalDate date) {
        return userId + ":" + loanType + ":" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
