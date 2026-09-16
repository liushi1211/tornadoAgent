package com.tornado.domain.memory.gateway;

/** 记忆运行参数网关（domain 定义，infrastructure 绑定 saa.memory.* 实现） */
public interface MemorySettingsGateway {

    /** 注入长期记忆的条数上限 */
    int injectTopk();
}
