package com.tornado.common.context;

/** 登录用户上下文（ThreadLocal），由 JwtAuthFilter 注入，Service 层取 userId 做数据隔离 */
public final class UserContext {

    public record LoginUser(Long userId, String username) {}

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    public static void set(LoginUser user) { HOLDER.set(user); }

    public static LoginUser get() { return HOLDER.get(); }

    public static Long userId() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.userId();
    }

    public static String username() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.username();
    }

    public static void clear() { HOLDER.remove(); }
}
