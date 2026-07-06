package com.ghyinc.finance.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(this.info())
                .servers(this.servers());
    }

    private Info info() {
        return new Info()
                .title("비교금리 제휴대출 API")
                .description("""
                        ## 비교금리 제휴대출 API
                        
                        제휴 금융사(카카오뱅크, 토스뱅크, 라인뱅크 등)를 대상으로
                        대출 한도조회 및 결과 수신, 대출신청을 처리하는 API 서버입니다.
                        
                        ### 주요 흐름
                        1. FE -> 한도조회 요청 (`POST /api/loan/request-compare-loan`)
                        2. 비동기로 금융사 API 병렬 전송
                        3. FE 폴링으로 결과 조회 (`GET /api/loan/inquiry/{inquiryNo}`)
                        4. 고객 선택 후 대출신청 (`POST /api/loan/apply`)
                        """)
                .version("1.0.0")
                .contact(new Contact()
                        .name("윤교희")
                        .email("gyohee91@gmail.com")
                        .url("https://github.com/gyohee91/bigin")
                );
    }

    private List<Server> servers() {
        return List.of(
                new Server()
                        .url("http://localhost:8080")
                        .description("Local")
        );
    }
}
