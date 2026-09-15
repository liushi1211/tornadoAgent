package com.tornado.boot.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.common.context.UserContext;
import com.tornado.common.entity.User;
import com.tornado.common.ex.BizException;
import com.tornado.common.ex.ErrorCode;
import com.tornado.common.jwt.JwtService;
import com.tornado.common.mapper.UserMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** 注册/登录/刷新/登出（登出为无状态简化实现，refresh 黑名单可后续扩展） */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public record RegisterReq(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$", message = "用户名需为3-32位字母数字下划线") String username,
                              @NotBlank @Size(min = 8, max = 32, message = "密码长度 8-32") String password,
                              @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "邮箱格式不正确") String email,
                              String nickname) {}

    public record LoginReq(@NotBlank String username, @NotBlank String password) {}

    public record RefreshReq(@NotBlank String refreshToken) {}

    public record TokenPair(String accessToken, String refreshToken, long expiresIn, UserVO user) {}

    public record UserVO(Long id, String username, String nickname, String email) {}

    private final UserMapper userMapper;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public TokenPair register(RegisterReq req) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, req.username())) > 0) {
            throw new BizException(ErrorCode.AUTH_USER_EXISTS, "用户名已存在");
        }
        if (req.email() != null && !req.email().isBlank()
                && userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, req.email())) > 0) {
            throw new BizException(ErrorCode.AUTH_USER_EXISTS, "邮箱已存在");
        }
        User u = new User();
        u.setUsername(req.username());
        u.setEmail(req.email() == null || req.email().isBlank() ? null : req.email());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setNickname(req.nickname() == null || req.nickname().isBlank() ? req.username() : req.nickname());
        u.setStatus(1);
        userMapper.insert(u);
        return issue(u);
    }

    public TokenPair login(LoginReq req) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.username()));
        if (u == null || !encoder.matches(req.password(), u.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_BAD_CREDENTIALS);
        }
        if (u.getStatus() != null && u.getStatus() == 2) {
            throw new BizException(ErrorCode.AUTH_BAD_CREDENTIALS, "账号已禁用");
        }
        return issue(u);
    }

    public TokenPair refresh(String refreshToken) {
        UserContext.LoginUser lu = jwtService.parseRefresh(refreshToken);
        if (lu == null) {
            throw new BizException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        User u = userMapper.selectById(lu.userId());
        if (u == null) {
            throw new BizException(ErrorCode.AUTH_REFRESH_INVALID);
        }
        return issue(u);
    }

    public UserVO me() {
        Long uid = UserContext.userId();
        User u = userMapper.selectById(uid);
        if (u == null) {
            throw new BizException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        return toVO(u);
    }

    public UserVO updateNickname(String nickname) {
        User u = userMapper.selectById(UserContext.userId());
        if (u == null) {
            throw new BizException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        u.setNickname(nickname);
        userMapper.updateById(u);
        return toVO(u);
    }

    private TokenPair issue(User u) {
        JwtService.TokenPair tp = jwtService.issue(u.getId(), u.getUsername());
        return new TokenPair(tp.accessToken(), tp.refreshToken(), tp.expiresIn(), toVO(u));
    }

    private UserVO toVO(User u) {
        return new UserVO(u.getId(), u.getUsername(), u.getNickname(), u.getEmail());
    }
}
