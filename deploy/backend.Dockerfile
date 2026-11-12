# Image du back-end ESSOR QMS, construction multi-étape.
# Contexte de construction : ../backend (voir docker-compose.yml).

FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21.0.13_9-jre-alpine
RUN addgroup -S essor && adduser -S essor -G essor \
    && mkdir -p /var/lib/essor/files && chown -R essor:essor /var/lib/essor
WORKDIR /app
COPY --from=build /src/target/essor-qms-backend.jar app.jar
USER essor
ENV ESSOR_STORAGE_DIR=/var/lib/essor/files \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://localhost:8080/api/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
