package com.tornado.boot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.tornado")
@MapperScan({"com.tornado.common.mapper", "com.tornado.infrastructure"})
public class TornadoAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(TornadoAgentApplication.class, args);
    }
}
