package com.tornado.infrastructure.auth.gateway;

import com.tornado.domain.auth.gateway.TokenGateway;
import com.tornado.infrastructure.auth.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** TokenGateway 的 jjwt 实现（技术细节封装在 infrastructure） */
@Slf4j
@Component
public class JwtTokenGateway implements TokenGateway {

    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final JwtProperties props;
    private final SecretKey key;

    public JwtTokenGateway(JwtProperties props) {
        this.props = props;
        byte[] k = props.getSecret().getBytes(StandardCharsets.UTF_8);
        if (k.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(k, 0, padded, 0, k.length);
            k = padded;
        }
        this.key = Keys.hmacShaKeyFor(k);
    }

    @Override
    public IssuedTokens issue(Long userId, String username) {
        return new IssuedTokens(
                token(userId, username, TYPE_ACCESS, props.getAccessTtlMinutes() * 60_000L),
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

    @Override
    public Principal parseAccess(String jwt) {
        return parse(jwt, TYPE_ACCESS);
    }

    @Override
    public Principal parseRefresh(String jwt) {
        return parse(jwt, TYPE_REFRESH);
    }

    private Principal parse(String jwt, String expectType) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload();
            if (!expectType.equals(c.get(CLAIM_TYPE, String.class))) {
                return null;
            }
            return new Principal(Long.valueOf(c.getSubject()), c.get("username", String.class));
        } catch (Exception e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
