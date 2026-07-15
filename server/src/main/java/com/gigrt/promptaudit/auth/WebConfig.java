package com.gigrt.promptaudit.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires {@link SecurityInterceptor} onto the /api/v1/prompts routes.
 * Public routes (no interceptor): POST/DELETE /api/v1/auth/* (login/logout) and /health.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtUtil jwt;

    @Value("${app.ingest.token:}")
    private String ingestToken;

    public WebConfig(JwtUtil jwt) { this.jwt = jwt; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SecurityInterceptor(jwt, ingestToken))
                .addPathPatterns("/api/v1/prompts", "/api/v1/prompts/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Dev convenience (vite dev server on :5173). In prod the SPA is same-origin behind the edge nginx.
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
