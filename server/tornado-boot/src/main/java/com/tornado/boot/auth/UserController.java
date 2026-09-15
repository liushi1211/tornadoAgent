package com.tornado.boot.auth;

import com.tornado.common.api.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    @GetMapping("/me")
    public Result<AuthService.UserVO> me() {
        return Result.ok(authService.me());
    }

    @PatchMapping("/me")
    public Result<AuthService.UserVO> updateMe(@RequestBody UpdateReq req) {
        return Result.ok(authService.updateNickname(req.getNickname()));
    }

    @Data
    public static class UpdateReq {
        private String nickname;
    }
}
