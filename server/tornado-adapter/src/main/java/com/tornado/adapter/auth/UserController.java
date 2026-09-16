package com.tornado.adapter.auth;

import com.tornado.app.auth.AuthService;
import com.tornado.client.api.Result;
import com.tornado.client.auth.cmd.UpdateNicknameCmd;
import com.tornado.client.auth.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    @GetMapping("/me")
    public Result<UserDTO> me() {
        return Result.ok(authService.me());
    }

    @PatchMapping("/me")
    public Result<UserDTO> updateMe(@RequestBody UpdateNicknameCmd req) {
        return Result.ok(authService.updateNickname(req.getNickname()));
    }
}
