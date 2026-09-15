package com.tornado.common.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tornado.common.api.Result;
import com.tornado.common.context.UserContext;
import com.tornado.common.ex.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Bearer 解析 -> UserContext；白名单 /api/auth/** */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final List<String> WHITELIST = List.of("/api/auth/**", "/error");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || WHITELIST.stream().anyMatch(p -> MATCHER.match(p, path))) {
            chain.doFilter(request, response);
            return;
        }
        String auth = request.getHeader("Authorization");
        UserContext.LoginUser user = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            user = jwtService.parseAccess(auth.substring(7));
        }
        if (user == null) {
            response.setStatus(ErrorCode.AUTH_INVALID_TOKEN.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ErrorCode.AUTH_INVALID_TOKEN)));
            return;
        }
        try {
            UserContext.set(user);
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
