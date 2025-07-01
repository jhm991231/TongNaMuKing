package com.tongnamuking.tongnamuking_backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI tongNaMuWangOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("통나무왕 API")
                        .description("치지직 채팅 통계 분석 시스템 API")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("통나무왕 팀")
                                .email("contact@tongnamuwang.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("로컬 개발 서버"),
                        new Server().url("https://api.tongnamuwang.com").description("운영 서버")
                ));
    }
}