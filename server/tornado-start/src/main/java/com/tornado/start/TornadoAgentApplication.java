package com.tornado.start;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动装配入口（COLA start 层）：组件扫描覆盖 com.tornado 下所有分层模块，
 * MyBatis Mapper 只扫 infrastructure（domain 仓储为接口、非 Mapper）。
 */
@SpringBootApplication(scanBasePackages = "com.tornado")
@MapperScan("com.tornado.infrastructure")
public class TornadoAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(TornadoAgentApplication.class, args);
    }
}
