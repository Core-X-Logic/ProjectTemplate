# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S zero && adduser -S -G zero zero

# R-01b: glob DEĞİL, sabit ad. `pom.xml`'deki <finalName>app</finalName> jar adını
# artifactId'den bağımsızlaştırdı; bu şablonu klonlayıp artifactId'yi değiştiren biri
# artık bu satıra dokunmak zorunda kalmaz. (Glob hâlâ dursaydı COPY hedefi bulamaz ve
# build patlardı — gürültülü, ama gereksiz bir kırılma.)
COPY --from=build /workspace/target/app.jar /app/app.jar

USER zero

EXPOSE 8080

# PROD-R20. The image carried no profile, no heap ceiling and no healthcheck.
#
# Profile: with no SPRING_PROFILES_ACTIVE the base config applies — cache `simple`, an empty CORS
# allowlist, springdoc closed, and no JWT secret at all, so PROD-R1 makes the container fail to start
# rather than sign with a known key. Fail-closed is the right outcome, but "the image does not boot
# unless the orchestrator remembers a variable" is not a deployment contract. Pinning prod here makes
# the default correct and still overridable (`docker run -e SPRING_PROFILES_ACTIVE=dev`).
ENV SPRING_PROFILES_ACTIVE=prod

# Heap: the JVM sizes itself from the *container* limit only when told to. Without this it reads the
# host's memory on some runtimes and gets OOM-killed under a limit it never saw.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Readiness, not liveness: readiness is readinessState + db (PROD-R29 pins the group explicitly —
# Boot's default is readinessState alone, which reports READY while Postgres is unreachable), so a
# container that booted but cannot reach the database is held out of rotation instead of answering
# every request with a 500. Deliberately NOT /actuator/health: that one aggregates every indicator,
# so a secondary dependency being down would fail the container while it can still serve traffic.
# Anonymous by design (SecurityConfig permits /actuator/health/**) — the probe holds no credentials,
# and PROD-R17 deliberately did not widen its rule over this path.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

# Shell form so JAVA_OPTS expands; `exec` so the JVM stays PID 1 and receives SIGTERM for a graceful
# shutdown instead of being killed after the stop timeout.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
