# ---------- build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY tornado-common/pom.xml tornado-common/
COPY tornado-boot/pom.xml tornado-boot/
# 预拉依赖层（网络可用时）
RUN mvn -q -B dependency:go-offline || true
COPY tornado-common/src tornado-common/src
COPY tornado-boot/src tornado-boot/src
RUN mvn -q -B -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /src/tornado-boot/target/tornado-boot.jar app.jar
EXPOSE 8080
# JRE 镜像无 curl，用 bash /dev/tcp 探活
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
