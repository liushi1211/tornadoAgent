package com.tornado.client.auth.cmd;

import jakarta.validation.constraints.NotBlank;

/** 登录命令 */
public record LoginCmd(@NotBlank String username, @NotBlank String password) {}
