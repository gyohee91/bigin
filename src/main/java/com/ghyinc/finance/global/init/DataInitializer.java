package com.ghyinc.finance.global.init;

import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerType;
import com.ghyinc.finance.domain.loan.repository.PartnerRepository;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import com.ghyinc.finance.domain.user.entity.Member;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 서버 기동 시 Partner 테이블 초기 데이터 Insert
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {
    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;
    private final MemberRepository memberRepository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Partner> initialPartner = List.of(
                Partner.builder()
                        .partnerCode(PartnerCode.KAKAO_BANK)
                        .partnerName(PartnerCode.KAKAO_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK)
                        .active(true)
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.TOSS_BANK)
                        .partnerName(PartnerCode.TOSS_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK)
                        .active(true)
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.KB_CAPITAL)
                        .partnerName(PartnerCode.KB_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL)
                        .active(true)
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.K_BANK)
                        .partnerName(PartnerCode.K_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK)
                        .active(true)
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.LINE_BANK)
                        .partnerName(PartnerCode.LINE_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK)
                        .active(true)
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.SHINHAN_BANK)
                        .partnerName(PartnerCode.SHINHAN_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK)
                        .active(true)
                        .build()
        );
        partnerRepository.saveAll(initialPartner);

        List<Product> initialProduct = List.of(
                Product.builder()
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .partner(
                                initialPartner.stream()
                                        .filter(partner -> Objects.equals(PartnerCode.LINE_BANK, partner.getPartnerCode()))
                                        .findFirst()
                                        .orElse(null)
                        )
                        .productCode("P060100206")
                        .productName("사잇돌")
                        .build(),
                Product.builder()
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .partner(
                                initialPartner.stream()
                                        .filter(partner -> Objects.equals(PartnerCode.LINE_BANK, partner.getPartnerCode()))
                                        .findFirst()
                                        .orElse(null)
                        )
                        .productCode("P060100205")
                        .productName("드림론")
                        .build(),
                Product.builder()
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .partner(
                                initialPartner.stream()
                                        .filter(partner -> Objects.equals(PartnerCode.KAKAO_BANK, partner.getPartnerCode()))
                                        .findFirst()
                                        .orElse(null)
                        )
                        .productCode("TA")
                        .productName("갈아타기OK론")
                        .build(),
                Product.builder()
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .partner(
                                initialPartner.stream()
                                        .filter(partner -> Objects.equals(PartnerCode.TOSS_BANK, partner.getPartnerCode()))
                                        .findFirst()
                                        .orElse(null)
                        )
                        .productCode("FNQ005")
                        .productName("kiwi비상금")
                        .build()
        );
        productRepository.saveAll(initialProduct);

        List<Member> initialUser = List.of(
                Member.builder()
                        .name("윤교희")
                        .mobile("01056677055")
                        .email("gyohee91@gmail.com")
                        .build()
        );

        memberRepository.saveAll(initialUser);
    }
}
