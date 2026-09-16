package com.tornado.adapter.auth;

import com.tornado.app.auth.AuthService;
import com.tornado.client.api.Result;
import com.tornado.client.auth.cmd.LoginCmd;
import com.tornado.client.auth.cmd.RefreshCmd;
import com.tornado.client.auth.cmd.RegisterCmd;
import com.tornado.client.auth.dto.TokenPairDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<TokenPairDTO> register(@Valid @RequestBody RegisterCmd cmd) {
        return Result.ok(authService.register(cmd));
    }

    @PostMapping("/login")
    public Result<TokenPairDTO> login(@Valid @RequestBody LoginCmd cmd) {
        return Result.ok(authService.login(cmd));
    }

    @PostMapping("/refresh")
    public Result<TokenPairDTO> refresh(@Valid @RequestBody RefreshCmd cmd) {
        return Result.ok(authService.refresh(cmd.refreshToken()));
    }

    /** 无状态 JWT，登出仅需前端丢弃 token（简化：不维护黑名单） */
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.ok();
    }
}
