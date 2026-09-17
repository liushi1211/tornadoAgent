# ---------- build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# M6 起为 COLA 六模块多模块 reactor：client/domain/infrastructure/app/adapter/start
# （tornado-common/tornado-boot 已退役）。可执行产物在 tornado-start。
COPY pom.xml ./
COPY tornado-client/pom.xml            tornado-client/
COPY tornado-domain/pom.xml            tornado-domain/
COPY tornado-infrastructure/pom.xml    tornado-infrastructure/
COPY tornado-app/pom.xml               tornado-app/
COPY tornado-adapter/pom.xml           tornado-adapter/
COPY tornado-start/pom.xml             tornado-start/
# 预拉依赖层（网络可用时加速；离线/失败不阻断，下面带源码再 package 会补拉）
RUN mvn -q -B dependency:go-offline || true

COPY tornado-client/src            tornado-client/src
COPY tornado-domain/src            tornado-domain/src
COPY tornado-infrastructure/src    tornado-infrastructure/src
COPY tornado-app/src               tornado-app/src
COPY tornado-adapter/src           tornado-adapter/src
COPY tornado-start/src             tornado-start/src
RUN mvn -q -B -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
# 可执行 fat jar（spring-boot-maven-plugin repackage 产物）
COPY --from=build /src/tornado-start/target/tornado-start.jar app.jar
EXPOSE 8080
# JRE 镜像无 curl，用 bash /dev/tcp 探活
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
