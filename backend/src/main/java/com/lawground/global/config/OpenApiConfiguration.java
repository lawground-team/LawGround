package com.lawground.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    public OpenAPI lawGroundOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("LawGround API")
                                .version("v1")
                                .description(
                                        "문제 CRUD·제출 목록·개발 Mock 분석 API. 실제 모델·Google 로그인은 후속 구현입니다."));
    }

    @Bean
    public org.springdoc.core.customizers.OpenApiCustomizer csrfHeaderDocumentation() {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths()
                    .values()
                    .forEach(
                            path ->
                                    path.readOperationsMap()
                                            .forEach(
                                                    (method, operation) -> {
                                                        if (java.util.Set.of(
                                                                                io.swagger.v3.oas
                                                                                        .models
                                                                                        .PathItem
                                                                                        .HttpMethod
                                                                                        .POST,
                                                                                io.swagger.v3.oas
                                                                                        .models
                                                                                        .PathItem
                                                                                        .HttpMethod
                                                                                        .PUT,
                                                                                io.swagger.v3.oas
                                                                                        .models
                                                                                        .PathItem
                                                                                        .HttpMethod
                                                                                        .PATCH,
                                                                                io.swagger.v3.oas
                                                                                        .models
                                                                                        .PathItem
                                                                                        .HttpMethod
                                                                                        .DELETE)
                                                                        .contains(method)
                                                                && (operation.getParameters()
                                                                                == null
                                                                        || operation
                                                                                .getParameters()
                                                                                .stream()
                                                                                .noneMatch(
                                                                                        p ->
                                                                                                "X-CSRF-TOKEN"
                                                                                                                .equals(
                                                                                                                        p
                                                                                                                                .getName())
                                                                                                        && "header"
                                                                                                                .equals(
                                                                                                                        p
                                                                                                                                .getIn()))))
                                                            operation.addParametersItem(
                                                                    new io.swagger.v3.oas.models
                                                                                    .parameters
                                                                                    .Parameter()
                                                                            .name("X-CSRF-TOKEN")
                                                                            .in("header")
                                                                            .required(true)
                                                                            .description(
                                                                                    "같은 브라우저 세션에서 CSRF API로 받은 token")
                                                                            .schema(
                                                                                    new io.swagger
                                                                                            .v3.oas
                                                                                            .models
                                                                                            .media
                                                                                            .StringSchema()));
                                                    }));
        };
    }
}
