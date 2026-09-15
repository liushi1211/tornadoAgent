package com.tornado.boot.auth;

import com.tornado.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<AuthService.TokenPair> register(@Valid @RequestBody AuthService.RegisterReq req) {
        return Result.ok(authService.register(req));
    }

    @PostMapping("/login")
    public Result<AuthService.TokenPair> login(@Valid @RequestBody AuthService.LoginReq req) {
        return Result.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public Result<AuthService.TokenPair> refresh(@Valid @RequestBody AuthService.RefreshReq req) {
        return Result.ok(authService.refresh(req.refreshToken()));
    }

    /** 无状态 JWT，登出仅需前端丢弃 token（简化：不维护黑名单） */
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.ok();
    }
}
