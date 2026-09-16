package com.tornado.client.auth.dto;

/** 双 token 签发结果（对外契约） */
public record TokenPairDTO(String accessToken, String refreshToken, long expiresIn, UserDTO user) {}
