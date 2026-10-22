package com.tornado.start;

import com.tornado.adapter.web.CatBootstrap;
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
        // 必须在 Spring 启动前抢跑：cat-client 是懒加载且 s_init 一次性，进程内任何更早触碰 Cat 的代码
        // （典型是启动期一条 SQL 触发 CatMybatisInterceptor）都会用缺失的 client.xml 完成初始化并把客户端永久禁用。
        CatBootstrap.initFromSystemProperties();
        SpringApplication.run(TornadoAgentApplication.class, args);
    }
}
