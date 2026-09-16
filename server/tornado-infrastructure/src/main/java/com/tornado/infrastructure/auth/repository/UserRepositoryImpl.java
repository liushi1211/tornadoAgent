package com.tornado.infrastructure.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tornado.domain.auth.model.User;
import com.tornado.domain.auth.repository.UserRepository;
import com.tornado.infrastructure.auth.dataobject.UserDO;
import com.tornado.infrastructure.auth.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** 用户仓储实现：DO⇄领域对象转换 + 封装 MyBatis-Plus，domain/app 不感知 ORM */
@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final UserMapper userMapper;

    @Override
    public boolean existsByUsername(String username) {
        return userMapper.selectCount(new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, username)) > 0;
    }

    @Override
    public boolean existsByEmail(String email) {
        return userMapper.selectCount(new LambdaQueryWrapper<UserDO>().eq(UserDO::getEmail, email)) > 0;
    }

    @Override
    public User save(User user) {
        if (user.getId() == null) {
            UserDO d = toDO(user);
            userMapper.insert(d);
            user.setId(d.getId());
            return user;
        }
        // 更新：加载现有 DO 后仅覆盖可变字段，避免时间戳/未提交字段被清空
        UserDO existing = userMapper.selectById(user.getId());
        if (existing == null) {
            throw new IllegalStateException("用户不存在，id=" + user.getId());
        }
        existing.setNickname(user.getNickname());
        existing.setEmail(user.getEmail());
        existing.setStatus(user.getStatus());
        existing.setPasswordHash(user.getPasswordHash());
        userMapper.updateById(existing);
        return user;
    }

    @Override
    public User findByUsername(String username) {
        UserDO d = userMapper.selectOne(new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, username));
        return d == null ? null : toDomain(d);
    }

    @Override
    public User findById(Long id) {
        UserDO d = userMapper.selectById(id);
        return d == null ? null : toDomain(d);
    }

    private UserDO toDO(User u) {
        UserDO d = new UserDO();
        d.setId(u.getId());
        d.setUsername(u.getUsername());
        d.setEmail(u.getEmail());
        d.setPasswordHash(u.getPasswordHash());
        d.setNickname(u.getNickname());
        d.setStatus(u.getStatus());
        return d;
    }

    private User toDomain(UserDO d) {
        User u = new User();
        u.setId(d.getId());
        u.setUsername(d.getUsername());
        u.setEmail(d.getEmail());
        u.setPasswordHash(d.getPasswordHash());
        u.setNickname(d.getNickname());
        u.setStatus(d.getStatus());
        return u;
    }
}
