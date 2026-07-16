package com.gigrt.promptaudit.auth;

import com.gigrt.promptaudit.tenant.TenantService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires {@link SecurityInterceptor} onto the protected /api/v1 routes.
 * Public routes (no interceptor): POST/DELETE /api/v1/auth/* (login/logout) and /health.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtUtil jwt;
    private final TenantService tenants;

    public WebConfig(JwtUtil jwt, TenantService tenants) { this.jwt = jwt; this.tenants = tenants; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SecurityInterceptor(jwt, tenants))
                .addPathPatterns("/api/v1/prompts", "/api/v1/prompts/**",
                        "/api/v1/tenants", "/api/v1/tenants/**",
                        "/api/v1/my", "/api/v1/my/**",
                        "/api/v1/integrity");
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
