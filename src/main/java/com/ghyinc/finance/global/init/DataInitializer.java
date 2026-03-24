package com.ghyinc.finance.global.init;

import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.entity.PartnerLoanType;
import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerType;
import com.ghyinc.finance.domain.loan.repository.PartnerLoanTypeRepository;
import com.ghyinc.finance.domain.loan.repository.PartnerRepository;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import com.ghyinc.finance.domain.user.entity.Member;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;
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
    private final PartnerLoanTypeRepository partnerLoanTypeRepository;
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
                        .algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("wvtX75QJj1Uw1xKqw2kyPOVNBAmDr2vr")
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.TOSS_BANK)
                        .partnerName(PartnerCode.TOSS_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK)
                        .active(true)
                        .algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("bd0001eb9404dc257b90547d1343c4de")
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.KB_CAPITAL)
                        .partnerName(PartnerCode.KB_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL)
                        .active(true)
                        .algorithm(CryptoAlgorithm.RSA_OAEP)
                        .publicKey("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyxkSWuJRXR27YstInQ+0\n" +
                                "Dohwf82TcqvYtCeW1My8wNoBVhArdT9SBGdcU3z4RYEqioRPm4MCoaJBwAlL/wv6\n" +
                                "Tp3+3ZkDlINrv/cTOxfvRBSB4rd6EVHNT53oXUx7mRFPv0/uOdCyKlELtTcCRriO\n" +
                                "R9mPHY/b99clKS/NYPvPgNa+H5dhnX08wa4wm3n+N+uAjVtppKWdq+aGPBFyU50q\n" +
                                "xmVvQV9JcdX25CyNB9djtWt1EfJ2qq1NqTt06ciTwbu3pyDuRLByjB5HtusSRJrj\n" +
                                "kKZ0MWPgSgfDcvXk4GzA9UBpnYc25cl0L3JTtjeZDSnX4lgeoc7x8x6sxsnvDsXt\n" +
                                "KQIDAQAB")
                        .privateKey("MIIEpAIBAAKCAQEAyxkSWuJRXR27YstInQ+0Dohwf82TcqvYtCeW1My8wNoBVhAr\n" +
                                "dT9SBGdcU3z4RYEqioRPm4MCoaJBwAlL/wv6Tp3+3ZkDlINrv/cTOxfvRBSB4rd6\n" +
                                "EVHNT53oXUx7mRFPv0/uOdCyKlELtTcCRriOR9mPHY/b99clKS/NYPvPgNa+H5dh\n" +
                                "nX08wa4wm3n+N+uAjVtppKWdq+aGPBFyU50qxmVvQV9JcdX25CyNB9djtWt1EfJ2\n" +
                                "qq1NqTt06ciTwbu3pyDuRLByjB5HtusSRJrjkKZ0MWPgSgfDcvXk4GzA9UBpnYc2\n" +
                                "5cl0L3JTtjeZDSnX4lgeoc7x8x6sxsnvDsXtKQIDAQABAoIBAQCi0T+owoSNzLcb\n" +
                                "lXJqD1u+xtzBaFILfP6mNpKxmEy9oket8hqUzSV4SFB40dfLCKjNERMszZN/dq+V\n" +
                                "Px7AoZ6SBhF7Hx8CoXTxGSc+mYqEHpid448lcVnRuPq+SQFRDdLLwU1u5gLe78ge\n" +
                                "B7J4dZ4CtcQI4/ppLv4ojZztYhHQ62k4Gwwy1lqlfVM08+jVcJlpgDOjt+KZpExA\n" +
                                "Uf4cUoMOg7cx0o8M1ROZYWxaPBXFNF9v4QsRZcpeKwqIqlpWsHch89NAx1oMUVVx\n" +
                                "K1nl4Xj2KtMZw32Af1eGAQldSO3qCy70PiEeqOAQE/j6TZPxw5upX1KA7o348yB5\n" +
                                "yACd+J8BAoGBAOSihM5r3OZcUtx3zPAE5U/+4VabzflXEs0Owi0nquyCgy0wdlPs\n" +
                                "PzYJOk2KSWqkoUOnEOZlAxLOoNz2haiA1FV6EriHXmhs87IvVCuFGkpzZ1FDRGCF\n" +
                                "QWz5IbJPbPt3R7IOGE2+guDZt4TAcNjthXBN3AUOj+r18Jka2aqlZMyJAoGBAONo\n" +
                                "FwgqVFKxi15rTB8f+uVRlhJLVzRLNEdbCFzJmGLkZGzjcfsziz4Ch+uJp+oZuvQQ\n" +
                                "1CtLsqkcU/eTbGEwZENz0LAWO3D/eSkMlRkiWbI+bM0GHNocbZ4wTw6/HxYkm64f\n" +
                                "+kd1S5m6WR90HpKO+42nhaBap+es5iaXn1n0EjOhAoGANHDydUZYTJ4wg1EXOJZm\n" +
                                "4opbtTnXbLGEJnSUJTdMBSOKYvsSqP0vIn3LWa22WTeZpaLURYQ1yEKMsyH4VkX2\n" +
                                "bgSp9plWFi2nV99zNug4t4rwz7rWHC10bEJYcEW3gZZCY5zIBk0ER/6oEVLyj08r\n" +
                                "pC63oJFOgV4X6YY3FuUI0cECgYEAljnHLU+5UL+VEBTVvqIDvsX8260FuLgNmy3a\n" +
                                "AmHy1zGF3iEKxSWx0I8fd0wCrzW8OUt8vfVN20Wpep3bNQEg2yaBMDIfpnA+fA2h\n" +
                                "2W7FzmhKu85T9Qpep+fF8jnzsU8RwR/C2L316WIfShYNtEfciiGmtt3smbGwgMId\n" +
                                "NPF1rMECgYAoKyRsqnw6HMGvWQanBn7pHhy2Kh3bxdwu/0j+1+5hF17FmGVnvMxE\n" +
                                "heJWnTHr2tnB4QnauZsCafZmNzl8u52Nds4QKlBLFRLhmQEmsO1EpkE8ZLJu+jwQ\n" +
                                "gaWmBN05DhJC2NpwBJ/o7lWvGRUgsT6X4LWJaPgVfWpAjjUDT44x5g==")
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.K_BANK)
                        .partnerName(PartnerCode.K_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK)
                        .active(true)
                        .algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("AAEGRJuHwTWvrYsaa0V7vAqk+wZuSa2l")
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.LINE_BANK)
                        .partnerName(PartnerCode.LINE_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK)
                        .active(true)
                        .algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("AAEGRJuHwTWvrYsaa0V7vAqk+wZuSa2l")
                        .build(),
                Partner.builder()
                        .partnerCode(PartnerCode.SHINHAN_BANK)
                        .partnerName(PartnerCode.SHINHAN_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK)
                        .active(true)
                        .algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("AAEGRJuHwTWvrYsaa0V7vAqk+wZuSa2l")
                        .build()
        );
        partnerRepository.saveAll(initialPartner);

        List<PartnerLoanType> initialPartnerLoanType = List.of(
                PartnerLoanType.builder()
                        .partner(
                                initialPartner.stream()
                                        .filter(partner -> Objects.equals(PartnerCode.KAKAO_BANK, partner.getPartnerCode()))
                                        .findFirst()
                                        .orElse(null)
                        )
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .active(true)
                        .build(),
                PartnerLoanType.builder()
                        .partner(
                                initialPartner.stream()
                                        .filter(partner -> Objects.equals(PartnerCode.TOSS_BANK, partner.getPartnerCode()))
                                        .findFirst()
                                        .orElse(null)
                        )
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .active(true)
                        .build(),
                PartnerLoanType.builder()
                        .partner(
                                initialPartner.stream()
                                        .filter(partner -> Objects.equals(PartnerCode.LINE_BANK, partner.getPartnerCode()))
                                        .findFirst()
                                        .orElse(null)
                        )
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .active(true)
                        .build(),
                PartnerLoanType.builder()
                        .partner(
                                initialPartner.stream()
                                        .filter(partner -> Objects.equals(PartnerCode.KB_CAPITAL, partner.getPartnerCode()))
                                        .findFirst()
                                        .orElse(null)
                        )
                        .loanType(LoanType.BUSINESS)
                        .active(true)
                        .build()
        );

        partnerLoanTypeRepository.saveAll(initialPartnerLoanType);

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
                        .active(true)
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
                        .active(true)
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
                        .active(true)
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
                        .active(true)
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
