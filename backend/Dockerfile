FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace/backend
COPY backend/ ./
COPY frontend/src/ /workspace/frontend/src/
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --create-home lawground
WORKDIR /app
COPY --from=builder /workspace/backend/build/libs/lawground-backend-*.jar app.jar
USER lawground
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
