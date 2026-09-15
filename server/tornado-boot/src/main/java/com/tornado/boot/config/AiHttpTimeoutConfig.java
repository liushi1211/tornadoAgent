package com.tornado.boot.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 模型调用超时双通道：阻塞通道走 RestClient（RestClientCustomizer 生效），
 * 流式通道走 WebClient（WebClientCustomizer 生效）。classpath 无 reactor-netty，
 * 用 JDK HttpClient（JdkClientHttpConnector）配超时。
 */
@Configuration
public class AiHttpTimeoutConfig {

    @Bean
    public RestClientCustomizer restClientCustomizer() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(60))
                .withReadTimeout(Duration.ofSeconds(120));
        return builder -> {
            ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
            builder.requestFactory(requestFactory);
        };
    }

    @Bean
    public WebClientCustomizer webClientCustomizer() {
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();
        JdkClientHttpConnector connector = new JdkClientHttpConnector(jdkClient);
        connector.setReadTimeout(Duration.ofSeconds(300));
        return builder -> builder.clientConnector(connector);
    }
}
