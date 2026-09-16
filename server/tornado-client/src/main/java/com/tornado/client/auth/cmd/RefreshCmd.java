package com.tornado.client.auth.cmd;

import jakarta.validation.constraints.NotBlank;

/** 刷新令牌命令 */
public record RefreshCmd(@NotBlank String refreshToken) {}
