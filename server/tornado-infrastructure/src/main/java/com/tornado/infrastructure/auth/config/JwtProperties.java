package com.tornado.infrastructure.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** JWT 配置：HS256 双 token（access 2h / refresh 7d） */
@Data
@Component
@ConfigurationProperties(prefix = "saa.jwt")
public class JwtProperties {
    private String secret = "tornado-dev-jwt-secret-please-change-0123456789abcdef";
    private long accessTtlMinutes = 120;
    private long refreshTtlDays = 7;
}
