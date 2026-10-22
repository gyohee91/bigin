package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.*;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitResult;
import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import com.ghyinc.finance.domain.loan.repository.PartnerRepository;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitSummaryService {
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final LoanLimitProductResultRepository loanLimitProductResultRepository;
    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public LoanLimitSummaryResponse getCompareLoanSummary(String inquiryNo) {
        LoanLimitInquiry inquiry = loanLimitInquiryRepository.findByInquiryNo(inquiryNo)
                .orElseThrow(() -> new InvalidRequestException("존재하지 않는 이력조회입니다: " + inquiryNo));

        List<LoanLimitProductResultResponse> results =
                loanLimitProductResultRepository.findAllByInquiryNo(inquiry.getId());

        List<PartnerCode> partnerCodes = inquiry.getResults().stream()
                .map(LoanLimitResult::getPartnerCode)
                .toList();

        Map<PartnerCode, String> partnerNameMap = partnerRepository.findActiveByPartnerCodes(partnerCodes)
                .stream().collect(Collectors.toMap(Partner::getPartnerCode, Partner::getPartnerName, ((s, s2) -> s)));

        Set<String> codes = results.stream()
                .map(LoanLimitProductResultResponse::productCode)
                .collect(Collectors.toSet());
        Map<String, String> productNameMap = productRepository.findNamesByProductCodes(codes).stream()
                .collect(Collectors.toMap(
                        p -> p.partnerCode() + ":" + p.productCode(),
                        ProductNameDto::productName,
                        (s, s2) -> s
                ));

        Map<PartnerCode, List<LoanLimitProductResultResponse>> byPartner = results.stream()
                .collect(Collectors.groupingBy(LoanLimitProductResultResponse::partnerCode));

        // 상품명 -> 오퍼 목록 (등장 순서 유지)
        Map<String, List<ProductOfferSummary>> offersByProduct = new LinkedHashMap<>();
        List<UnavailablePartnerSummary> unavailablePartners = new ArrayList<>();

        for (LoanLimitResult partnerResult : inquiry.getResults()) {
            PartnerCode partnerCode = partnerResult.getPartnerCode();
            List<LoanLimitProductResultResponse> productResults = byPartner.getOrDefault(partnerCode, List.of());

            List<LoanLimitProductResultResponse> available = productResults.stream()
                    .filter(r -> Objects.nonNull(r.resultCode()) && r.resultCode().isSuccess()
                            && Objects.nonNull(r.amount()) && r.amount() > 0)
                    .toList();

            if (!available.isEmpty()) {
                String partnerName = partnerNameMap.getOrDefault(partnerCode, partnerCode.getPartnerName());
                for(LoanLimitProductResultResponse response : available) {
                    String productName = productNameMap.getOrDefault(response.partnerCode().name() + ":" + response.productCode(), response.productCode());
                    offersByProduct.computeIfAbsent(productName, s -> new ArrayList<>())
                            .add(ProductOfferSummary.builder()
                                    .partnerCode(partnerCode)
                                    .partnerName(partnerName)
                                    .productCode(response.productCode())
                                    .amount(response.amount())
                                    .interestRate(response.interestRate())
                                    .build()
                            );
                }
                continue;
            }

            if (!isAllTerminal(partnerResult, productResults)) {
                continue;   // 아직 응답 대기중인 금융사: 가능/불가 어느 쪽에도 넣지 않음
            }

            unavailablePartners.add(
                    UnavailablePartnerSummary.builder()
                            .partnerCode(partnerResult.getPartnerCode())
                            .partnerName(partnerNameMap.getOrDefault(partnerCode, partnerCode.getPartnerName()))
                            .build()
            );
        }

        List<ProductGroupSummary> availableProducts = offersByProduct.entrySet().stream()
                .map(e -> {
                    List<ProductOfferSummary> offers = e.getValue().stream()
                            .sorted(Comparator.comparing(ProductOfferSummary::amount, Comparator.reverseOrder()))
                            .toList();
                    return ProductGroupSummary.builder()
                            .productName(e.getKey())
                            .lowestInterestRate(offers.stream().mapToDouble(ProductOfferSummary::interestRate).min().orElse(0))
                            .highestInterestAmount(offers.stream().mapToLong(ProductOfferSummary::amount).max().orElse(0))
                            .offers(offers)
                            .build();
                })
                .sorted(Comparator.comparing(ProductGroupSummary::highestInterestAmount).reversed())
                .toList();

        return LoanLimitSummaryResponse.builder()
                .inquiryNo(inquiry.getInquiryNo())
                .loanType(inquiry.getLoanType())
                .status(inquiry.getStatus())
                .availableProducts(availableProducts)
                .unavailablePartners(unavailablePartners)
                .build();
    }

    /**
     * 금융사의 모든 상품이 종료(결과 수신 또는 전송 실패)되었는지 판단한다.
     * 상품 행이 아직 없으면 대기 중으로 본다
     */
    private boolean isAllTerminal(LoanLimitResult partnerResult, List<LoanLimitProductResultResponse> productResults) {
        if (partnerResult.getStatus() == InquiryStatus.FAILED) return true;
        if (productResults.isEmpty()) return false;
        return productResults.stream()
                .allMatch(r -> r.resultCode() != null || r.status() == PartnerInquiryStatus.SEND_FAILED);
    }
}
