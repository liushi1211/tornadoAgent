package com.tornado.domain.auth.gateway;

/** 口令哈希网关（domain 定义，infrastructure 用 BCrypt 实现），使领域/应用层不依赖 spring-security */
public interface PasswordEncoderGateway {
    String encode(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}
