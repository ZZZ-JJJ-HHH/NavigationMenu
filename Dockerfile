# ---- 构建阶段：下载依赖 -> 编译打包 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom.xml，让依赖层能够命中缓存：改代码不会重新下载依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- 运行阶段：只带 JRE 和 jar ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# curl 供容器 HEALTHCHECK 使用
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app app

COPY --from=build /build/target/*.jar app.jar

# 应用以非 root 用户运行，日志目录必须先建好并交给它，否则文件日志写不进去
RUN mkdir -p /app/logs && chown -R app:app /app

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
