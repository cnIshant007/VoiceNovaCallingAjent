# -------- BUILD STAGE --------
# Use Java 21 so the compiled classes (target 65) match the runtime
FROM gradle:8.5-jdk21 AS build

WORKDIR /app

# Copy everything (fix your previous issue)
COPY . .

RUN chmod +x gradlew

RUN ./gradlew :backend:shadowJar --no-daemon --stacktrace

# -------- RUNTIME STAGE --------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update \
    && apt-get install -y curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/backend/build/libs/*-all.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
