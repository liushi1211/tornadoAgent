package com.tornado.adapter.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS 兜底（本地 5173 直连 8080 调试、以及 Nginx 入口 http://localhost:8085 带 Origin 的浏览器请求） */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                // 用 pattern 才能与 allowCredentials 共存并放行任意本地端口（5173 开发 / 8085 Nginx 入口 / LAN 端口）
                // 生产若用固定域名访问，追加如 "https://your.domain"
                .allowedOriginPatterns(
                        "http://localhost:[*]",
                        "http://127.0.0.1:[*]",
                        "http://host.docker.internal:[*]")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
