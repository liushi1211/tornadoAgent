package com.tornado.client.auth.dto;

/** 用户展示对象（对外契约，替代原 MyBatis 实体直接返回） */
public record UserDTO(Long id, String username, String nickname, String email) {}
