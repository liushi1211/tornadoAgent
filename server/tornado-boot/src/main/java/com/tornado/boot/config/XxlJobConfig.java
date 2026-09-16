package com.tornado.boot.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class XxlJobConfig {
    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${app.name}")
    private String appName;

    @Value("${xxl.job.executor.ip:}")
    private String ip;

    @Value("${xxl.job.executor.port:9999}")
    private int port;

    /** 调度中心 3.x 默认 accessToken=default_token，执行器必须配同值，否则注册报 The access token is wrong */
    @Value("${xxl.job.access-token:}")
    private String accessToken;

    @Value("${xxl.job.executor.log-path:logs/xxl-job}")
    private String logPath;

    @Value("${xxl.job.executor.log-retention-days:30}")
    private int logRetentionDays;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info(">>>>>>>>>>> XXL-JOB 执行器初始化开始");
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appName);
        executor.setAccessToken(accessToken);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        log.info(">>>>>>>>>>> XXL-JOB 执行器初始化完成");
        return executor;
    }
}
