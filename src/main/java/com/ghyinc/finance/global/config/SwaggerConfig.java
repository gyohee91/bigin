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
                .title("프로젝트명 API")
                .description("프로젝트 설명")
                .version("1.0.0")
                .contact(new Contact()
                        .name("윤교희")
                        .email("gyohee91@gmail.com")
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
