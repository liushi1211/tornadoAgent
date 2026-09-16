package com.tornado.app.auth;

import com.tornado.client.auth.cmd.LoginCmd;
import com.tornado.client.auth.cmd.RegisterCmd;
import com.tornado.client.auth.dto.TokenPairDTO;
import com.tornado.client.auth.dto.UserDTO;
import com.tornado.client.context.UserContext;
import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import com.tornado.domain.auth.gateway.PasswordEncoderGateway;
import com.tornado.domain.auth.gateway.TokenGateway;
import com.tornado.domain.auth.model.User;
import com.tornado.domain.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 认证应用服务：编排注册/登录/刷新，事务边界所在层。
 * 只依赖 domain 接口（UserRepository/TokenGateway/PasswordEncoderGateway）与 client 契约，不感知 ORM/JWT 技术细节。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TokenGateway tokenGateway;
    private final PasswordEncoderGateway passwordEncoder;

    public TokenPairDTO register(RegisterCmd cmd) {
        if (userRepository.existsByUsername(cmd.username())) {
            throw new BizException(ErrorCode.AUTH_USER_EXISTS, "用户名已存在");
        }
        boolean hasEmail = cmd.email() != null && !cmd.email().isBlank();
        if (hasEmail && userRepository.existsByEmail(cmd.email())) {
            throw new BizException(ErrorCode.AUTH_USER_EXISTS, "邮箱已存在");
        }
        User u = new User();
        u.setUsername(cmd.username());
        u.setEmail(hasEmail ? cmd.email() : null);
        u.setPasswordHash(passwordEncoder.encode(cmd.password()));
        u.setNickname(cmd.nickname() == null || cmd.nickname().isBlank() ? cmd.username() : cmd.nickname());
        u.setStatus(1);
        userRepository.save(u);
        return issue(u);
    }

    public TokenPairDTO login(LoginCmd cmd) {
        User u = userRepository.findByUsername(cmd.username());
        if (u == null || !passwordEncoder.matches(cmd.password(), u.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_BAD_CREDENTIALS);
        }
        u.assertActive();
        return issue(u);
    }

    public TokenPairDTO refresh(String refreshToken) {
        TokenGateway.Principal p = tokenGateway.parseRefresh(refreshToken);
        if (p == null) {
            throw new BizException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        User u = userRepository.findById(p.userId());
        if (u == null) {
            throw new BizException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        return issue(u);
    }

    public UserDTO me() {
        User u = userRepository.findById(UserContext.userId());
        if (u == null) {
            throw new BizException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        return toDTO(u);
    }

    public UserDTO updateNickname(String nickname) {
        User u = userRepository.findById(UserContext.userId());
        if (u == null) {
            throw new BizException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        u.rename(nickname);
        userRepository.save(u);
        return toDTO(u);
    }

    private TokenPairDTO issue(User u) {
        TokenGateway.IssuedTokens t = tokenGateway.issue(u.getId(), u.getUsername());
        return new TokenPairDTO(t.accessToken(), t.refreshToken(), t.expiresIn(), toDTO(u));
    }

    private UserDTO toDTO(User u) {
        return new UserDTO(u.getId(), u.getUsername(), u.getNickname(), u.getEmail());
    }
}
