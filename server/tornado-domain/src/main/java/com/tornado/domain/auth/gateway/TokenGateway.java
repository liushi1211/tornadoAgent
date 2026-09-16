package com.tornado.domain.auth.gateway;

/**
 * 令牌网关（domain 定义，infrastructure 用 jjwt 实现）。
 * 领域/应用层只依赖此接口，不感知 JWT 技术细节。
 */
public interface TokenGateway {

    record IssuedTokens(String accessToken, String refreshToken, long expiresIn) {}

    /** 令牌主体（userId + username） */
    record Principal(Long userId, String username) {}

    IssuedTokens issue(Long userId, String username);

    /** 解析并校验 access 令牌，非法/过期返回 null */
    Principal parseAccess(String jwt);

    /** 解析并校验 refresh 令牌，非法/过期返回 null */
    Principal parseRefresh(String jwt);
}
