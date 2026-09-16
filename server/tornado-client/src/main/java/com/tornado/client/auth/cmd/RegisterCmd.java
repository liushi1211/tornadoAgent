package com.tornado.client.auth.cmd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 注册命令 */
public record RegisterCmd(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$", message = "用户名需为3-32位字母数字下划线") String username,
        @NotBlank @Size(min = 8, max = 32, message = "密码长度 8-32") String password,
        @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "邮箱格式不正确") String email,
        String nickname) {}
