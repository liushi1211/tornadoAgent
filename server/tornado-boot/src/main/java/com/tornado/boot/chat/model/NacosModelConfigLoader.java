package com.tornado.boot.chat.model;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.tornado.boot.chat.model.ChatModelProperties.ModelDef;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Nacos 模型清单热更新：nacos-client（gRPC 长连接）启动拉全量 + addListener 实时推送，
 * 配置变更秒级生效；另保留 5 分钟一次的对账轮询兜底（防连接抖动漏推送）。
 * saa.nacos.enabled=false 时该 Bean 不创建，仅用本地 application.yml 兜底。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "saa.nacos", name = "enabled", havingValue = "true")
public class NacosModelConfigLoader {

    private final ModelConfigHolder holder;
    private final NacosProperties props;
    private volatile ConfigService configService;

    public NacosModelConfigLoader(ModelConfigHolder holder, NacosProperties props) {
        this.holder = holder;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            Properties p = new Properties();
            p.put(PropertyKeyConst.SERVER_ADDR, props.getServerAddr());
            if (props.getNamespace() != null && !props.getNamespace().isBlank()) {
                p.put(PropertyKeyConst.NAMESPACE, props.getNamespace());
            }
            if (props.getUsername() != null && !props.getUsername().isBlank()) {
                p.put(PropertyKeyConst.USERNAME, props.getUsername());
                p.put(PropertyKeyConst.PASSWORD, props.getPassword());
            }
            configService = NacosFactory.createConfigService(p);
            String content = configService.getConfig(props.getDataId(), props.getGroup(), 5000);
            apply(content, "启动加载");
            configService.addListener(props.getDataId(), props.getGroup(), new Listener() {
                @Override
                public Executor getExecutor() {
                    return null; // Nacos 内部线程，回调仅替换内存清单，很快
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    apply(configInfo, "实时推送");
                }
            });
            log.info("Nacos 监听已注册: namespace={} group={} dataId={}",
                    props.getNamespace(), props.getGroup(), props.getDataId());
        } catch (Exception e) {
            log.warn("Nacos 初始化失败，继续使用本地兜底配置: {}", e.getMessage());
        }
    }

    /** 对账兜底：5 分钟主动拉一次（正常时与监听内容一致，replace 幂等）；初始化失败时此处重试 */
    @Scheduled(initialDelay = 300_000, fixedDelay = 300_000)
    public void reconcile() {
        if (configService == null) {
            init();
            return;
        }
        try {
            apply(configService.getConfig(props.getDataId(), props.getGroup(), 3000), "对账拉取");
        } catch (Exception e) {
            log.warn("Nacos 对账拉取失败（监听仍有效）: {}", e.getMessage());
        }
    }

    private void apply(String yaml, String scene) {
        if (yaml == null || yaml.isBlank()) {
            log.warn("Nacos {} 返回空内容，保持现有清单", scene);
            return;
        }
        try {
            List<ModelDef> defs = parse(yaml);
            if (!defs.isEmpty()) {
                holder.replace(defs, defaultFrom(yaml));
                log.info("Nacos 模型清单已{}: {} 个模型", scene, defs.size());
            }
        } catch (Exception e) {
            log.warn("Nacos {} 内容解析失败，保持现有清单: {}", scene, e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (configService != null) {
            try {
                configService.shutDown();
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<ModelDef> parse(String yaml) {
        Map<String, Object> root = new Yaml().load(yaml);
        Map<String, Object> chat = root == null ? null : (Map<String, Object>) root.get("chat");
        List<Map<String, Object>> models = chat == null ? null : (List<Map<String, Object>>) chat.get("models");
        List<ModelDef> defs = new ArrayList<>();
        if (models != null) {
            for (Map<String, Object> m : models) {
                defs.add(toDef(m));
            }
        }
        return defs;
    }

    @SuppressWarnings("unchecked")
    private String defaultFrom(String yaml) {
        Map<String, Object> root = new Yaml().load(yaml);
        Map<String, Object> chat = root == null ? null : (Map<String, Object>) root.get("chat");
        Object d = chat == null ? null : chat.get("default-model");
        return d == null ? holder.defaultModel() : d.toString();
    }

    private ModelDef toDef(Map<String, Object> m) {
        ModelDef d = new ModelDef();
        d.setId(str(m, "id"));
        d.setProvider(m.getOrDefault("provider", "dashscope").toString());
        d.setApiKey(str(m, "api-key"));
        d.setBaseUrl(str(m, "base-url"));
        d.setDisplayName(str(m, "display-name"));
        Object st = m.get("supports-thinking");
        d.setSupportsThinking(st != null && Boolean.parseBoolean(st.toString()));
        Object t = m.get("temperature");
        d.setTemperature(t == null ? null : Double.valueOf(t.toString()));
        Object mt = m.get("max-tokens");
        d.setMaxTokens(mt == null ? null : Integer.valueOf(mt.toString()));
        return d;
    }

    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    @Data
    @Component
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "saa.nacos")
    public static class NacosProperties {
        private boolean enabled;
        private String serverAddr = "localhost:8848";
        private String namespace = "";
        private String group = "CHAT";
        private String dataId = "chat-models.yaml";
        /** 服务端开启鉴权时配置（建议环境变量注入） */
        private String username = "";
        private String password = "";
    }
}
