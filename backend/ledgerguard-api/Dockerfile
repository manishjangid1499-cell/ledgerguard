# Stage 1: Build stage
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY backend/ledgerguard-api/pom.xml backend/ledgerguard-api/
COPY backend/psp-simulator/pom.xml backend/psp-simulator/
COPY backend/notification-worker/pom.xml backend/notification-worker/
COPY backend/failure-lab/pom.xml backend/failure-lab/
COPY backend/e2e-tests/pom.xml backend/e2e-tests/

COPY backend/ledgerguard-api/src backend/ledgerguard-api/src

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && \
    ./mvnw -pl backend/ledgerguard-api -am package -DskipTests

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system --gid 1001 ledgerguard && \
    useradd --system --uid 1001 --gid ledgerguard --no-create-home ledgerguard

COPY --from=builder --chown=ledgerguard:ledgerguard /workspace/backend/ledgerguard-api/target/ledgerguard-api-0.1.0-SNAPSHOT-exec.jar /app/app.jar

USER ledgerguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
