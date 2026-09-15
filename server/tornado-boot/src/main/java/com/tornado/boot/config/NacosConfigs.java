package com.tornado.boot.config;

import com.alibaba.cloud.nacos.annotation.NacosConfig;
import com.tornado.boot.chat.model.ChatModelProperties;
import lombok.Getter;
import org.springframework.stereotype.Component;


@Component
@Getter
public class NacosConfigs {

    @NacosConfig(dataId = "chat-models.json", group = "DEFAULT_GROUP")
    private ChatModelProperties chatModelProperties;

}
