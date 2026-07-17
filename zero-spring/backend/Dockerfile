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

COPY --from=build /workspace/target/zero-platform-*.jar /app/app.jar

USER zero

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
