package com.tornado.domain.rag.gateway;

/** RAG 运行参数网关（domain 定义，infrastructure 绑定 saa.rag.* 实现），让应用层不感知配置来源 */
public interface RagSettingsGateway {

    int chunkChars();

    int chunkOverlap();

    /** 检索默认 topK */
    int topk();

    /** 单文件大小上限（MB） */
    long maxFileSizeMb();
}
