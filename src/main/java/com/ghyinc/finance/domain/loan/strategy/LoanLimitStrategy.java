package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

import java.util.List;

/**
 * 대출 유형별 한도조회 전략 인터페이스
 *
 * <p>신용대출, 오토담보, 주담대 등 대출 유형마다 지원 금융사, 유효성 검증 규칙,
 * 외부 데이터 조회 여부, 어댑터 요청 변환 방식이 상이하므로 Strategy 패턴으로 분리한다.
 * 새로운 대출 유형 추가 시 본 인터페이스를 구현하는 것만으로 전략이 자동 등록된다.</p>
 *
 * <h3>역할 분담</h3>
 * <ul>
 *     <li><b>Strategy</b>: 어떤 금융사에 조회할지, 요청을 어떻게 구성할지 결정</li>
 *     <li><b>Adaptor</b>: 해당 금융사 API를 어떻게 호출할지 담당</li>
 *     <li><b>Service</b>: 전략을 선택하고 어댑터를 조율</li>
 * </ul>
 *
 * <h3>구현체 목록</h3>
 * <ul>
 *     <li>{@code PersonalLoanLimitStrategy}: 신용대출 (외부 데이터 불필요)</li>
 *     <li>{@code AutoLoanLimitStrategy}: 오토담보 (Nice DNR 갑·을구 조회 필요)</li>
 *     <li>{@code MortgageLoanLimitStrategy}: 주담대 (KB 부동산 시세 조회 필요)</li>
 * </ul>
 *
 * @see com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory
 * @see com.ghyinc.finance.domain.loan.service.LoanLimitService
 */
public interface LoanLimitStrategy {
    /**
     * 이 전락이 처리하는 대출 유형을 반환한다
     *
     * <p>{@link com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory}가
     * {@code LoanType}을 키로 전략을 등록하고 조회할 때 사용한다.</p>
     *
     * @return  담당 대출유형
     */
    LoanType getLoanType();

    /**
     * 이 대출유형에서 조회 가능 금융사 목록을 반환한다
     *
     * <p>대출유형에 따라 지원 금융사가 다르며 코드 레벨에서 고정 관리한다.
     * 운영팀이 배포 없이 금융사를 제어할 수 있도록 DB 활성화 여부는
     * {@link com.ghyinc.finance.domain.loan.service.LoanLimitService}에서 별도 운영</p>
     *
     * @return  이 대출유형을 지원하는 금융사 코드 목록
     */
    List<PartnerCode> getSupportedBanks();

    /**
     * 대출유형별 필수 입력값 유효성을 검증한다.
     *
     * <p>공통 유효성 검증 이후 대출유형별 추가 검증을 수행한다.
     * ex) 신용대출: creditScore 필수, 오토담보: carNo 필수</p>
     *
     * @param request   한도조회 요청 DTO
     * @throws org.apache.kafka.common.errors.InvalidRequestException   유효성 검증 실패 시
     */
    void validate(LoanLimitRequest request);

    /**
     * 대출유형에 필요한 외부 데이터를 조회하여 {@link ExternalDataContext}로 반환한다.
     *
     * <p>{@link #requiresExternalData()}가 {@code true}인 경우에만 호출된다.
     * 조회 실패 시 {@code ExternalDataContext.errors}에 오류 정보를 포함하여 반환하며
     * {@link #filterAvailablePartners(List, ExternalDataContext)}에서 해당 금융사를 제외한다.</p>
     *
     * <p>ex) 오토담보: Nice DNR 갑·을구 조회, 주담대: KB부동산 시세 조회</p>
     *
     * @param request   한도조회 요청 DTO
     * @return          외부 데이터 조회 결과 (조회 실패 시 errors 포함)
     */
    ExternalDataContext fetchExternalData(LoanLimitRequest request);

    /**
     * 서비스 요청 DTO를 금융사 전송용 어댑터 요청 DTO로 변환한다
     *
     * <p>대출유형별로 금융사에 전송해야 하는 필드와 매핑 방식이 다르다.
     * ex) 오토담보는 Nice DNR 조회 결과(차량 정보)를 추가로 포함한다.</p>
     *
     * @param request   한도조회 요청 DTO
     * @param externalDataContext 외부 데이터 조회 결과 (Nice DNR, KB 시세 등)
     * @return          금융사 전송용 공통 어댑터 요청 DTO
     */
    LoanLimitAdaptorRequest toAdaptorRequest(LoanLimitRequest request, ExternalDataContext externalDataContext);

    /**
     * 금융사 API 전송 전 외부 데이터 조회 필요 여부를 반환한다.
     *
     * <p>{@code false}인 경우 {@link #fetchExternalData(LoanLimitRequest)}를
     * 호출하지 않으며, {@link ExternalDataContext#empty()}가 사용된다.</p>
     *
     * @return  외부 데이터 조회가 필요하면 {@code true}
     */
    boolean requiresExternalData();

    /**
     * 외부 API 실패 시 진행 가능한 금융사만 필터링하여 반환한다.
     *
     * <p>기본 구현은 {@code activePartnerCodes}를 그대로 반환한다.
     * 외부 데이터가 필요한 대출 유형(오토담보, 주담대)은 본 메서드를 재정의하여
     * 조회 실패한 경우 해당 금융사를 제외한다.</p>
     *
     * <p>ex) Nice DNR 오류 시 차량 정보가 필요한 오토담보 금융사를 제외하고
     * 나머지 금융사로만 한도조회를 진행한다.</p>
     *
     * @param activePartnerCodes    활성화된 전체 금융사 목록
     * @param context               외부 데이터 조회 결과 (errors 포함 가능)
     * @return  실제 조회 가능한 금융사 목록 (기본: 전체 그대로 반환)
     */
    default List<PartnerCode> filterAvailablePartners(List<PartnerCode> activePartnerCodes, ExternalDataContext context) {
        return activePartnerCodes;  // 기본: 전체 진행
    }

    /**
     * 어댑터 응답에 대한 대출유형별 후처리를 수행한다.
     *
     * <p>기본 구현은 응답을 그대로 반환한다.
     * 대출유형별 추가 후처리가 필요한 경우 재정의한다.
     * ex) 응답 코드 재매핑, 특정 조건에 따른 결과 보정</p>
     *
     * @param response  어댑터 응답 DTO
     * @return  후처리된 어댑터 응답 DTO (기본: 응답 그대로 반환)
     */
    default LoanLimitAdaptorResponse postProcess(LoanLimitAdaptorResponse response) {
        return response;
    }
}
