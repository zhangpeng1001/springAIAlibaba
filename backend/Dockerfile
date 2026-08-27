# 后端容器：构建阶段固定 Java 21，以匹配 Maven release 21 编译目标。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/knowledge-agent-0.1.0-SNAPSHOT.jar app.jar
VOLUME ["/app/data", "/app/answer"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
