# Build JAR locally first: cd auth-service && ../gradlew bootJar
# Runtime stage only - expects build/libs/auth-service-*.jar in context

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY build/libs/*.jar app.jar

RUN mkdir -p /etc/auth-service/keys && \
    chown -R appuser:appgroup /etc/auth-service /app

USER appuser

EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8083/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
