package com.tornado.infrastructure.auth.gateway;

import com.tornado.domain.auth.gateway.PasswordEncoderGateway;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** PasswordEncoderGateway 的 BCrypt 实现 */
@Component
public class BCryptPasswordEncoderGateway implements PasswordEncoderGateway {

    private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();

    @Override
    public String encode(String rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return delegate.matches(rawPassword, encodedPassword);
    }
}
