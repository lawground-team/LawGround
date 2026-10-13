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
                                        "문제 CRUD·제출 목록·Mock 분석·OIDC/JDBC 인증 API. Google 실계정·실제 AI·프런트 화면은 별도 검증입니다."));
    }

    @Bean
    public org.springdoc.core.customizers.OpenApiCustomizer csrfHeaderDocumentation() {
        return api -> {
            if (api.getPaths() == null) return;
            // Logout is implemented by the security filter, so MVC scanning cannot discover it.
            api.getPaths()
                    .addPathItem(
                            "/api/v1/auth/logout",
                            new io.swagger.v3.oas.models.PathItem()
                                    .post(
                                            new io.swagger.v3.oas.models.Operation()
                                                    .operationId("logoutCurrentSession")
                                                    .summary("현재 브라우저 세션 로그아웃")
                                                    .responses(
                                                            new io.swagger.v3.oas.models.responses
                                                                            .ApiResponses()
                                                                    .addApiResponse(
                                                                            "204",
                                                                            new io.swagger.v3.oas
                                                                                            .models
                                                                                            .responses
                                                                                            .ApiResponse()
                                                                                    .description(
                                                                                            "현재 세션과 쿠키 삭제, 본문 없음"))
                                                                    .addApiResponse(
                                                                            "403",
                                                                            new io.swagger.v3.oas
                                                                                            .models
                                                                                            .responses
                                                                                            .ApiResponse()
                                                                                    .description(
                                                                                            "CSRF 누락 또는 불일치")))));
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
