package com.tornado.infrastructure.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tornado.infrastructure.auth.dataobject.UserDO;

/** users 表 Mapper（在 @MapperScan 的 infrastructure 包内被扫描） */
public interface UserMapper extends BaseMapper<UserDO> {
}
