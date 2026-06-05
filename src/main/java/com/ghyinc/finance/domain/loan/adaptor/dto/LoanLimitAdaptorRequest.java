package com.ghyinc.finance.domain.loan.adaptor.dto;

import com.ghyinc.finance.domain.external.coocon.dto.RespData;
import com.ghyinc.finance.domain.external.nice.dto.AutoInfo;
import com.ghyinc.finance.domain.external.nice.dto.AutoSecondInfo;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.RequestProduct;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.Builder;

import java.util.List;

/**
 * 금융사 한도조회 API 전송용 공통 요청 DTO
 *
 * <p>{@link com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy#toAdaptorRequest(LoanLimitRequest, ExternalDataContext)}에서
 * 생성되며, 금융사별 {@link com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor}가
 * 자사 API 명세에 맞는 요청 포맷으로 변환하여 사용한다.</p>
 *
 * <h3>필드 구성 방식</h3>
 * <p>대출유형에 따라 사용되는 필드가 다르며, 사용하지 않는 필드는 {@code null}로 전달된다.</p>
 * <ul>
 *     <li>신용대출: {@code requestProducts}, {@code name}, {@code rrno}, {@code jobType}, {@code jobName}, {@code joinDate}</li>
 *     <li>오토담보: 신용대출 필드 + {@code carNo}, {@code autoInfo}, {@code autoSecondInfo}</li>
 *     <li>주담대: 신용대출 필드 + {@code address}, {@code respData}</li>
 * </ul>
 *
 * @see com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor#inquireLimit(PartnerCode, LoanLimitAdaptorRequest)
 * @see RequestProduct
 * 
 * @param requestProducts   금융사에 전송할 상품별 신청번호 + 상품코드 목록
 * @param name      고객 성명
 * @param rrno      주민번호
 * @param jobType   직업 유형
 * @param jobName   직장명 or 사업장명
 * @param joinDate  입사일 or 사업장명
 * @param loanType  대출유형
 * @param carNo     차량번호
 * @param address   주소
 * @param agreePersonalCreditInfo   개인신용정보 조회 동의 여부
 * @param agreePersonalCreditTime   개인신용정보 조회 동의 시간
 * @param autoInfo          Nice DNR 갑구
 * @param autoSecondInfo    Nice DNR 을구
 * @param respData          KB부동산 시세
 */
@Builder(toBuilder = true)
public record LoanLimitAdaptorRequest(
        List<RequestProduct> requestProducts,
        String name,
        String rrno,
        JobType jobType,
        String jobName,
        String joinDate,
        LoanType loanType,
        String carNo,
        String address,
        boolean agreePersonalCreditInfo,
        String agreePersonalCreditTime,
        AutoInfo autoInfo,
        AutoSecondInfo autoSecondInfo,
        RespData respData
) {
}
