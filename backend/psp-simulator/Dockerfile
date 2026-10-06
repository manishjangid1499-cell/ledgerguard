# Stage 1: Build stage
FROM eclipse-temurin:22-jdk-jammy AS builder
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY backend/ledgerguard-api/pom.xml backend/ledgerguard-api/
COPY backend/psp-simulator/pom.xml backend/psp-simulator/
COPY backend/notification-worker/pom.xml backend/notification-worker/
COPY backend/failure-lab/pom.xml backend/failure-lab/
COPY backend/e2e-tests/pom.xml backend/e2e-tests/

COPY backend/psp-simulator/src backend/psp-simulator/src

RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && \
    ./mvnw -pl backend/psp-simulator -am package -DskipTests && \
    FOUND=$(find backend/psp-simulator/target -maxdepth 1 -type f -name 'psp-simulator-*.jar' ! -name '*.original' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-tests.jar' ! -name '*-exec.jar') && \
    COUNT=$(echo "$FOUND" | grep -c . || true) && \
    if [ "$COUNT" -ne 1 ]; then echo "Error: Expected exactly 1 jar, found $COUNT: $FOUND" >&2; exit 1; fi && \
    cp "$FOUND" /workspace/app.jar

# Stage 2: Runtime stage
FROM eclipse-temurin:22-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system --gid 1001 ledgerguard && \
    useradd --system --uid 1001 --gid ledgerguard --no-create-home ledgerguard

COPY --from=builder --chown=ledgerguard:ledgerguard /workspace/app.jar /app/app.jar

USER ledgerguard
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
