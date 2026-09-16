package com.tornado.domain.auth.repository;

import com.tornado.domain.auth.model.User;

/** 用户仓储接口（domain 定义，infrastructure 实现；屏蔽 MyBatis-Plus 细节） */
public interface UserRepository {
    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /** id 为空则 insert，否则 load-merge updateById；返回带 id 的领域对象 */
    User save(User user);

    User findByUsername(String username);

    User findById(Long id);
}
