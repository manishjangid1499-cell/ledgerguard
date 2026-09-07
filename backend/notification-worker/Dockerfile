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

COPY backend/notification-worker/src backend/notification-worker/src

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && \
    ./mvnw -pl backend/notification-worker -am package -DskipTests

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system --gid 1001 ledgerguard && \
    useradd --system --uid 1001 --gid ledgerguard --no-create-home ledgerguard

COPY --from=builder --chown=ledgerguard:ledgerguard /workspace/backend/notification-worker/target/notification-worker-0.1.0-SNAPSHOT.jar /app/app.jar

USER ledgerguard
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
