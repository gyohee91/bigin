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
import com.ghyinc.finance.domain.user.enums.MemberRole;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    private final PasswordEncoder passwordEncoder;

    private static final int TARGET_COUNT = 1000;

    private static final String[] SURNAMES = {"김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "서문", "남궁", "초", "차", "독고", "연"};
    private static final String[] GIVEN_NAMES = {
            "민준", "서연", "예준", "지우", "도윤", "하은", "시우", "지호", "수아", "은서", "진우", "태희", "혜교", "윤서",
            "지안", "서준", "하윤", "예은", "주원", "채원", "우진", "다은", "현우", "소율", "지현", "수연", "서현", "제하"
    };

    @Override
    public void run(ApplicationArguments args) {
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
                        .active(false)
                        .algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("AAEGRJuHwTWvrYsaa0V7vAqk+wZuSa2l")
                        .build(),
                // 시중은행
                Partner.builder().partnerCode(PartnerCode.KB_BANK).partnerName(PartnerCode.KB_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("CDrNQP3RgBUrIih0n8jJMtXohCdv6WL9").build(),
                Partner.builder().partnerCode(PartnerCode.WOORI_BANK).partnerName(PartnerCode.WOORI_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("XoolzTM6BFLpd0vuudf3dYUGSBggX7kh").build(),
                Partner.builder().partnerCode(PartnerCode.HANA_BANK).partnerName(PartnerCode.HANA_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("tDPVZbsCwH7NRu8iSrThmOuaX9UCNLpk").build(),
                Partner.builder().partnerCode(PartnerCode.NH_BANK).partnerName(PartnerCode.NH_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("nNeNk5Rwn7cXdQoxtsxyVeidEYqS0wv1").build(),
                Partner.builder().partnerCode(PartnerCode.IBK_BANK).partnerName(PartnerCode.IBK_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("mLeSfjvQJqIFqx7C5xYXkZtv4BGVGSx0").build(),
                Partner.builder().partnerCode(PartnerCode.SC_BANK).partnerName(PartnerCode.SC_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("R62VH9YCdpgw6BspElHJDsmaA95lLfz8").build(),
                Partner.builder().partnerCode(PartnerCode.CITI_BANK).partnerName(PartnerCode.CITI_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("IEe5YnGZy53zGseWs6eW4QYeQTpRMLit").build(),

                // 지방은행
                Partner.builder().partnerCode(PartnerCode.DGB_BANK).partnerName(PartnerCode.DGB_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("1RWw4Q3lioUAb0PdSRSCY5l24USEJzfm").build(),
                Partner.builder().partnerCode(PartnerCode.BUSAN_BANK).partnerName(PartnerCode.BUSAN_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("cGXya0GMYhy4OkJqWSqougw9wSNM8EIJ").build(),
                Partner.builder().partnerCode(PartnerCode.KYONGNAM_BANK).partnerName(PartnerCode.KYONGNAM_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("Uf0UJNhmvtWs2rpV5lY8kMOPz6adM8a0").build(),
                Partner.builder().partnerCode(PartnerCode.GWANGJU_BANK).partnerName(PartnerCode.GWANGJU_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("jQayNBhlFEdF7uhgZthBpLLExbUfXRnI").build(),
                Partner.builder().partnerCode(PartnerCode.JEONBUK_BANK).partnerName(PartnerCode.JEONBUK_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("odkCmQa9kjIWTDcfNvgh7qkqKcSB3Rkh").build(),
                Partner.builder().partnerCode(PartnerCode.JEJU_BANK).partnerName(PartnerCode.JEJU_BANK.getPartnerName())
                        .partnerType(PartnerType.BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("59eNm0qYZiHmWve2fpo2JkGhfPYbJOuD").build(),

                // 캐피탈 (KB_CAPITAL은 상단에 RSA_OAEP로 이미 등록되어 있어 제외)
                Partner.builder().partnerCode(PartnerCode.HYUNDAI_CAPITAL).partnerName(PartnerCode.HYUNDAI_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("DSZ5AycxHu1cyvVYVhkosWTLPfxuGxxs").build(),
                Partner.builder().partnerCode(PartnerCode.SHINHAN_CAPITAL).partnerName(PartnerCode.SHINHAN_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("7Kg1mcCufG1HQsszW67ACriaafjsCFZZ").build(),
                Partner.builder().partnerCode(PartnerCode.LOTTE_CAPITAL).partnerName(PartnerCode.LOTTE_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("k6wXDXzqOB497CKBryZxjz0U3T9iXn58").build(),
                Partner.builder().partnerCode(PartnerCode.HANA_CAPITAL).partnerName(PartnerCode.HANA_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("XOTJeP68ByCyAptUjXOyloepRAweGkPL").build(),
                Partner.builder().partnerCode(PartnerCode.WOORI_FINANCIAL_CAPITAL).partnerName(PartnerCode.WOORI_FINANCIAL_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("EjofrD2ZxLRHfrkLsz9tW1TnV3VJWzXq").build(),
                Partner.builder().partnerCode(PartnerCode.DGB_CAPITAL).partnerName(PartnerCode.DGB_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("ihtDJhCcHm9gbX69ErnnR9f6GhJdSDYH").build(),
                Partner.builder().partnerCode(PartnerCode.JB_WOORI_CAPITAL).partnerName(PartnerCode.JB_WOORI_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("8ntAHV6do0RKOpQV84gRigaYdXxiFmfd").build(),
                Partner.builder().partnerCode(PartnerCode.BNK_CAPITAL).partnerName(PartnerCode.BNK_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("yne7iC9nF3uX1SL9lIlcMACtTMkNsWgT").build(),
                Partner.builder().partnerCode(PartnerCode.IBK_CAPITAL).partnerName(PartnerCode.IBK_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("j19F6udilulOOYCD15J5HYO36ekdYuwK").build(),
                Partner.builder().partnerCode(PartnerCode.MERITZ_CAPITAL).partnerName(PartnerCode.MERITZ_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("BV3qI50ImTubfCORwDB2BaOTF8pXBPYb").build(),
                Partner.builder().partnerCode(PartnerCode.ORIX_CAPITAL).partnerName(PartnerCode.ORIX_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("YNsllJmFmbOx0P2Jnvyprt60mJsf57mx").build(),
                Partner.builder().partnerCode(PartnerCode.MIRAE_ASSET_CAPITAL).partnerName(PartnerCode.MIRAE_ASSET_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("GwKcthaZCDFxHfumLtIhyuJU5Yne0FMh").build(),
                Partner.builder().partnerCode(PartnerCode.ACUON_CAPITAL).partnerName(PartnerCode.ACUON_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("hZn7VK7zDyxEctWoPSs9ulkT6fQtjIdo").build(),
                Partner.builder().partnerCode(PartnerCode.KOREA_INVESTMENT_CAPITAL).partnerName(PartnerCode.KOREA_INVESTMENT_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("UiwWrfcWPJurL6psLbDZZmOnDdhtrKs5").build(),
                Partner.builder().partnerCode(PartnerCode.DB_CAPITAL).partnerName(PartnerCode.DB_CAPITAL.getPartnerName())
                        .partnerType(PartnerType.CAPITAL).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("RmL6DCRFpbAPoeeLKjMyb3VShoETUEpI").build(),

                // 저축은행
                Partner.builder().partnerCode(PartnerCode.SBI_SAVINGS_BANK).partnerName(PartnerCode.SBI_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("sAAwhWCDaKfYF7mn8dIIkngCDxOOirVB").build(),
                Partner.builder().partnerCode(PartnerCode.OK_SAVINGS_BANK).partnerName(PartnerCode.OK_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("CglTO8YdVggVd7o9zvabE63brSD04h83").build(),
                Partner.builder().partnerCode(PartnerCode.WELCOME_SAVINGS_BANK).partnerName(PartnerCode.WELCOME_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("dYRAuWA0iD22NdI7NrAj1c7gzwpttUkH").build(),
                Partner.builder().partnerCode(PartnerCode.PEPPER_SAVINGS_BANK).partnerName(PartnerCode.PEPPER_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("zBG1QqI5ME4W9xX39Up91gfFrMOgckQt").build(),
                Partner.builder().partnerCode(PartnerCode.ACUON_SAVINGS_BANK).partnerName(PartnerCode.ACUON_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("bcECzPWfcXQQwJOsx53MYvn2P3nYt2bn").build(),
                Partner.builder().partnerCode(PartnerCode.KOREA_INVESTMENT_SAVINGS_BANK).partnerName(PartnerCode.KOREA_INVESTMENT_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("sGHaMROWywEr5e8WQxQpzhIx44UzUB7y").build(),
                Partner.builder().partnerCode(PartnerCode.DAISHIN_SAVINGS_BANK).partnerName(PartnerCode.DAISHIN_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("49tsB39dBSSseUugOhHDVfN2pAyihw0p").build(),
                Partner.builder().partnerCode(PartnerCode.YUJIN_SAVINGS_BANK).partnerName(PartnerCode.YUJIN_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("P0SLyGWSWxIa4wQ5TL1dr6Ze46xao5Qg").build(),
                Partner.builder().partnerCode(PartnerCode.JT_CHINAE_SAVINGS_BANK).partnerName(PartnerCode.JT_CHINAE_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("l6mDp2Ic5R0oEdTFMF6yqqsl36Mqo2y5").build(),
                Partner.builder().partnerCode(PartnerCode.MOA_SAVINGS_BANK).partnerName(PartnerCode.MOA_SAVINGS_BANK.getPartnerName())
                        .partnerType(PartnerType.SAVINGS_BANK).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("1YpOb3zF5hrDMea5phg0YAdJqZ1sORzQ").build(),

                // 카드사
                Partner.builder().partnerCode(PartnerCode.SHINHAN_CARD).partnerName(PartnerCode.SHINHAN_CARD.getPartnerName())
                        .partnerType(PartnerType.CARD).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("nICvw3Rk2v4l4vhGZ6c1Inln7SybVGlE").build(),
                Partner.builder().partnerCode(PartnerCode.SAMSUNG_CARD).partnerName(PartnerCode.SAMSUNG_CARD.getPartnerName())
                        .partnerType(PartnerType.CARD).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("DcSGwv6Xx1NO2W7K00Omzj3efi0eOOOg").build(),
                Partner.builder().partnerCode(PartnerCode.KB_CARD).partnerName(PartnerCode.KB_CARD.getPartnerName())
                        .partnerType(PartnerType.CARD).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("WIOulrvpTDOy8OdfzF0Qc9QOCfpfqCwW").build(),
                Partner.builder().partnerCode(PartnerCode.HYUNDAI_CARD).partnerName(PartnerCode.HYUNDAI_CARD.getPartnerName())
                        .partnerType(PartnerType.CARD).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("crWXqnG7MTVkUFfgOh7B9IrL8rMA0OFu").build(),
                Partner.builder().partnerCode(PartnerCode.LOTTE_CARD).partnerName(PartnerCode.LOTTE_CARD.getPartnerName())
                        .partnerType(PartnerType.CARD).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("az2rrilKbghANEnbYb7m79avZppA4zP8").build(),
                Partner.builder().partnerCode(PartnerCode.WOORI_CARD).partnerName(PartnerCode.WOORI_CARD.getPartnerName())
                        .partnerType(PartnerType.CARD).active(true).algorithm(CryptoAlgorithm.AES_256_CBC)
                        .cryptoKey("mEjKfXzN3Rp8QvLdHwTsYuBcAgOi2Vqy").build()
        );
        partnerRepository.saveAll(initialPartner);

        Map<PartnerCode, Partner> partnerByCode = initialPartner.stream()
                .collect(Collectors.toMap(Partner::getPartnerCode, Function.identity()));
        
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
                        .build(),
                PartnerLoanType.builder()
                        .partner(
                                initialPartner.stream()
                                        .filter(partner -> Objects.equals(PartnerCode.SHINHAN_BANK, partner.getPartnerCode()))
                                        .findFirst()
                                        .orElse(null)
                        )
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .active(true)
                        .build(),
                // 시중은행
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.KB_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.WOORI_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.HANA_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.NH_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.IBK_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.SC_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.CITI_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),

                // 지방은행
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.DGB_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.BUSAN_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.KYONGNAM_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.GWANGJU_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.JEONBUK_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.JEJU_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),

                // 캐피탈
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.HYUNDAI_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.SHINHAN_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.LOTTE_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.HANA_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.WOORI_FINANCIAL_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.DGB_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.JB_WOORI_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.BNK_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.IBK_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.MERITZ_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.ORIX_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.MIRAE_ASSET_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.ACUON_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.KOREA_INVESTMENT_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.DB_CAPITAL)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),

                // 저축은행
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.SBI_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.OK_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.WELCOME_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.PEPPER_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.ACUON_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.KOREA_INVESTMENT_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.DAISHIN_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.YUJIN_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.JT_CHINAE_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.MOA_SAVINGS_BANK)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),

                // 카드사
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.SHINHAN_CARD)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.SAMSUNG_CARD)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.KB_CARD)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.HYUNDAI_CARD)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.LOTTE_CARD)).loanType(LoanType.PERSONAL_CREDIT).active(true).build(),
                PartnerLoanType.builder().partner(partnerByCode.get(PartnerCode.WOORI_CARD)).loanType(LoanType.PERSONAL_CREDIT).active(true).build()
        );
        partnerLoanTypeRepository.saveAll(initialPartnerLoanType);

        List<Product> initialProduct = List.of(
                Product.builder()
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .partner(partnerByCode.get(PartnerCode.LINE_BANK))
                        .productCode("P060100206")
                        .productName("사잇돌")
                        .active(true)
                        .build(),
                Product.builder()
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .partner(partnerByCode.get(PartnerCode.LINE_BANK))
                        .productCode("P060100205")
                        .productName("드림론")
                        .active(true)
                        .build(),
                Product.builder()
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .partner(partnerByCode.get(PartnerCode.KAKAO_BANK))
                        .productCode("TA")
                        .productName("갈아타기OK론")
                        .active(true)
                        .build(),
                Product.builder()
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .partner(partnerByCode.get(PartnerCode.TOSS_BANK))
                        .productCode("FNQ005")
                        .productName("kiwi비상금")
                        .active(true)
                        .build(),
                Product.builder()
                        .loanType(LoanType.PERSONAL_CREDIT)
                        .partner(partnerByCode.get(PartnerCode.SHINHAN_BANK))
                        .productCode("0201001074")
                        .productName("비상금신한론")
                        .active(true)
                        .build(),
                // 시중은행
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.KB_BANK))
                        .productCode("P070000001").productName("KB국민은행 직장인 신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.WOORI_BANK))
                        .productCode("P070000002").productName("우리은행 우량고객 신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.HANA_BANK))
                        .productCode("P070000003").productName("하나원큐 신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.NH_BANK))
                        .productCode("P070000004").productName("NH직장인대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.IBK_BANK))
                        .productCode("P070000005").productName("IBK i-ONE 신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.SC_BANK))
                        .productCode("P070000006").productName("SC 우량고객대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.CITI_BANK))
                        .productCode("P070000007").productName("씨티 프리미어 신용대출").active(true).build(),

                // 지방은행
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.DGB_BANK))
                        .productCode("P070000008").productName("DGB 데일리 신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.BUSAN_BANK))
                        .productCode("P070000009").productName("BNK 부산은행 직장인대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.KYONGNAM_BANK))
                        .productCode("P070000010").productName("경남은행 새희망대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.GWANGJU_BANK))
                        .productCode("P070000011").productName("광주은행 스마트론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.JEONBUK_BANK))
                        .productCode("P070000012").productName("전북은행 JB다이렉트대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.JEJU_BANK))
                        .productCode("P070000013").productName("제주은행 탐나는대출").active(true).build(),

                // 캐피탈
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.HYUNDAI_CAPITAL))
                        .productCode("P070000014").productName("현대캐피탈 프리미엄론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.SHINHAN_CAPITAL))
                        .productCode("P070000015").productName("신한캐피탈 패스트론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.LOTTE_CAPITAL))
                        .productCode("P070000016").productName("롯데캐피탈 스피드론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.HANA_CAPITAL))
                        .productCode("P070000017").productName("하나캐피탈 원터치대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.WOORI_FINANCIAL_CAPITAL))
                        .productCode("P070000018").productName("우리금융캐피탈 우리론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.DGB_CAPITAL))
                        .productCode("P070000019").productName("DGB캐피탈 다이렉트대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.JB_WOORI_CAPITAL))
                        .productCode("P070000020").productName("JB우리캐피탈 직장인대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.BNK_CAPITAL))
                        .productCode("P070000021").productName("BNK캐피탈 신속대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.IBK_CAPITAL))
                        .productCode("P070000022").productName("IBK캐피탈 신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.MERITZ_CAPITAL))
                        .productCode("P070000023").productName("메리츠캐피탈 스마트대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.ORIX_CAPITAL))
                        .productCode("P070000024").productName("오릭스캐피탈 원플러스대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.MIRAE_ASSET_CAPITAL))
                        .productCode("P070000025").productName("미래에셋캐피탈 프라임론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.ACUON_CAPITAL))
                        .productCode("P070000026").productName("애큐온캐피탈 직장인론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.KOREA_INVESTMENT_CAPITAL))
                        .productCode("P070000027").productName("한국투자캐피탈 신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.DB_CAPITAL))
                        .productCode("P070000028").productName("DB캐피탈 원데이대출").active(true).build(),

                // 저축은행
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.SBI_SAVINGS_BANK))
                        .productCode("P070000029").productName("SBI저축은행 사이다대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.OK_SAVINGS_BANK))
                        .productCode("P070000030").productName("OK저축은행 OK론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.WELCOME_SAVINGS_BANK))
                        .productCode("P070000031").productName("웰컴저축은행 웰컴론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.PEPPER_SAVINGS_BANK))
                        .productCode("P070000032").productName("페퍼저축은행 페퍼론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.ACUON_SAVINGS_BANK))
                        .productCode("P070000033").productName("애큐온저축은행 직장인대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.KOREA_INVESTMENT_SAVINGS_BANK))
                        .productCode("P070000034").productName("한국투자저축은행 신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.DAISHIN_SAVINGS_BANK))
                        .productCode("P070000035").productName("대신저축은행 새희망론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.YUJIN_SAVINGS_BANK))
                        .productCode("P070000036").productName("유진저축은행 유진론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.JT_CHINAE_SAVINGS_BANK))
                        .productCode("P070000037").productName("JT친애저축은행 친애론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.MOA_SAVINGS_BANK))
                        .productCode("P070000038").productName("모아저축은행 모아론").active(true).build(),

                // 카드사
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.SHINHAN_CARD))
                        .productCode("P070000039").productName("신한카드 신한 아무나론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.SAMSUNG_CARD))
                        .productCode("P070000040").productName("삼성카드 마이신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.KB_CARD))
                        .productCode("P070000041").productName("KB국민카드 직장인대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.HYUNDAI_CARD))
                        .productCode("P070000042").productName("현대카드 슈퍼신용대출").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.LOTTE_CARD))
                        .productCode("P070000043").productName("롯데카드 롯데론").active(true).build(),
                Product.builder().loanType(LoanType.PERSONAL_CREDIT).partner(partnerByCode.get(PartnerCode.WOORI_CARD))
                        .productCode("P070000044").productName("우리카드 우리V론").active(true).build()
        );
        productRepository.saveAll(initialProduct);


        List<Member> initialUser = new ArrayList<>(TARGET_COUNT);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // BCrypt는 1회 해시에 수십~백여ms가 걸려 TARGET_COUNT만큼 매번 encode()를 부르면
        // (예: 200건 * ~70ms ≈ 14초) 이 ApplicationRunner가 끝나기 전에 @KafkaListener가
        // 먼저 떠서 밀린 메시지를 처리하다가 아직 비어있는 member 테이블에 대고
        // EntityNotFoundException을 내는 원인이 된다. 부하테스트용 더미 계정이라 실제 로그인
        // 검증 대상이 아니므로 해시 1회만 계산해 재사용한다.
        String seedPassword = passwordEncoder.encode("test1234!");
        while (initialUser.size() < TARGET_COUNT) {
            initialUser.add(
                    Member.builder()
                            .name(this.randomName(rnd))
                            .mobile(this.randomMobile(rnd))
                            .email("user" + rnd.nextInt() + "@gmail.com")
                            .password(seedPassword)
                            .role(MemberRole.USER)
                            .build()
            );
        }

        memberRepository.saveAll(initialUser);
    }

    private String randomName(ThreadLocalRandom rnd) {
        String surname = SURNAMES[rnd.nextInt(SURNAMES.length)];
        String given = GIVEN_NAMES[rnd.nextInt(GIVEN_NAMES.length)];
        return surname + given;
    }

    private String randomMobile(ThreadLocalRandom rnd) {
        int mid = rnd.nextInt(1000, 10000);
        int last = rnd.nextInt(1000, 10000);
        return String.format("010-%04d-%04d", mid, last);
    }
}
