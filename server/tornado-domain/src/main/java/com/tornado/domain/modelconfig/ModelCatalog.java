package com.tornado.domain.modelconfig;

import com.tornado.domain.modelconfig.model.ModelDefinition;

import java.util.List;

/**
 * 模型清单目录（domain 端口，infrastructure 用 Nacos/本地兜底实现并缓存）。
 * 让应用/领域只感知“有哪些模型、默认哪个、版本几何”，不关心配置来源（Nacos gRPC 监听 / yml）。
 */
public interface ModelCatalog {

    List<ModelDefinition> list();

    ModelDefinition find(String id);

    /** 找不到指定 id 时回落到 default */
    ModelDefinition findOrDefault(String id);

    String defaultModel();

    /** 清单版本号：变更即 +1，供 ChatModelFactory 失效缓存重建 */
    long version();
}
