package com.tornado.common.jwt;

import com.tornado.common.context.UserContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** jjwt 双 token 签发/解析 */
@Slf4j
@Service
public class JwtService {

    public static final String CLAIM_TYPE = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties props;
    private final SecretKey key;

    public JwtService(JwtProperties props) {
        this.props = props;
        byte[] k = props.getSecret().getBytes(StandardCharsets.UTF_8);
        if (k.length < 32) {
            // HS256 要求 >=256bit，短 secret 补零（仅开发环境可能触发）
            byte[] padded = new byte[32];
            System.arraycopy(k, 0, padded, 0, k.length);
            k = padded;
        }
        this.key = Keys.hmacShaKeyFor(k);
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn) {}

    public TokenPair issue(Long userId, String username) {
        return new TokenPair(token(userId, username, TYPE_ACCESS, props.getAccessTtlMinutes() * 60_000L),
                token(userId, username, TYPE_REFRESH, props.getRefreshTtlDays() * 86_400_000L),
                props.getAccessTtlMinutes() * 60L);
    }

    private String token(Long userId, String username, String type, long ttlMs) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMs))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析并校验；非法/过期返回 null */
    public UserContext.LoginUser parse(String jwt, String expectType) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
            if (!expectType.equals(c.get(CLAIM_TYPE, String.class))) {
                return null;
            }
            return new UserContext.LoginUser(Long.valueOf(c.getSubject()), c.get("username", String.class));
        } catch (Exception e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public UserContext.LoginUser parseAccess(String jwt) { return parse(jwt, TYPE_ACCESS); }

    public UserContext.LoginUser parseRefresh(String jwt) { return parse(jwt, TYPE_REFRESH); }
}
