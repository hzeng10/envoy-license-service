FROM eclipse-temurin:21-jre AS base
WORKDIR /app

# Extract layered jar for efficient Docker layer caching
FROM base AS builder
COPY target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM base
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./

ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx512m -XX:MaxRAMPercentage=75.0"

EXPOSE 9191 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
