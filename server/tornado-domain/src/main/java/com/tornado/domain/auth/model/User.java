package com.tornado.domain.auth.model;

import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import lombok.Data;

/**
 * 用户聚合根（纯领域对象，零框架依赖）。
 * 密码哈希/令牌签发等技术细节通过 gateway 接口下沉到 infrastructure。
 */
@Data
public class User {
    private Long id;
    private String username;
    private String email;
    private String passwordHash;
    private String nickname;
    /** 1 正常 2 禁用 */
    private Integer status;

    public boolean isEnabled() {
        return status == null || status != 2;
    }

    public void rename(String newNickname) {
        this.nickname = newNickname;
    }

    /** 登录前置校验：账号未禁用 */
    public void assertActive() {
        if (!isEnabled()) {
            throw new BizException(ErrorCode.AUTH_BAD_CREDENTIALS, "账号已禁用");
        }
    }
}
