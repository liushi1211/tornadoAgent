package com.tornado.adapter.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 {@link CatFilter}（cat-client {@code com.dianping.cat.servlet.CatFilter} 的 jakarta 重写版）。
 * 默认关闭，需 {@code cat.enabled=true} 且 CAT Server 就绪后开启。
 */
@Configuration
@ConditionalOnProperty(prefix = "cat", name = "enabled", havingValue = "true")
public class CatWebConfig {

    @Bean
    public FilterRegistrationBean<CatFilter> catFilter() {
        FilterRegistrationBean<CatFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CatFilter());
        registration.addUrlPatterns("/*");
        registration.setName("cat-filter");
        // 置于过滤器链最前，尽量串起完整调用链（含鉴权失败请求）
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

}
