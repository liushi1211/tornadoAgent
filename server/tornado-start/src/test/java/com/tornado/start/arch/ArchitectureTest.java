package com.tornado.start.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * COLA 分层架构守护（M6）：绑定 test 阶段防回退。
 * 核心铁律：adapter → app → domain ← infrastructure，client 对全层可见且自身不反向依赖业务分层；domain 零框架依赖。
 */
@AnalyzeClasses(packages = "com.tornado", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** domain 层禁止依赖任何技术框架与上层模块（只允许 JDK / lombok / client 契约） */
    @ArchTest
    static final ArchRule domain_is_free_of_framework_and_upper_layers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.tornado.infrastructure..",
                    "com.tornado.app..",
                    "com.tornado.adapter..",
                    "com.tornado.start..",
                    "org.apache.ibatis..",
                    "com.baomidou..",
                    "redis.clients..",
                    "org.redisson..",
                    "org.springframework.data.redis..",
                    "org.springframework.web..",
                    "org.springframework.webflux..",
                    "org.springframework.ai..",
                    "com.alibaba.cloud.ai..",
                    "com.alibaba.nacos..",
                    "com.alibaba.cloud.nacos..",
                    "org.apache.pdfbox..",
                    "org.apache.poi..",
                    "io.jsonwebtoken..",
                    "com.xxl.job..");

    /** 依赖方向不得倒挂：domain 与 app 都不应引用 adapter；app 也不应引用 start */
    @ArchTest
    static final ArchRule no_upward_dependency_into_adapter = noClasses()
            .that().resideInAnyPackage("..domain..", "..app..", "..infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    /** infrastructure 只实现 domain 端口，不得反向依赖 app/adapter/start */
    @ArchTest
    static final ArchRule infrastructure_does_not_depend_on_app_or_adapter = noClasses()
            .that().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.tornado.app..", "com.tornado.adapter..", "com.tornado.start..");

    /** client 为共享契约层，不得依赖任何业务分层 */
    @ArchTest
    static final ArchRule client_is_dependency_free = noClasses()
            .that().resideInAPackage("com.tornado.client..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.tornado.domain..", "com.tornado.app..",
                    "com.tornado.adapter..", "com.tornado.infrastructure..", "com.tornado.start..");
}
