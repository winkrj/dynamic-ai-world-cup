# Build inputs contain no runtime credentials. Supply secrets only when starting the service.
FROM node:24-bookworm-slim AS web
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend ./frontend
COPY contracts/fixtures ./contracts/fixtures
RUN npm run build:web

FROM eclipse-temurin:21-jdk-jammy AS package
WORKDIR /workspace/backend
COPY backend ./
COPY --from=web /workspace/frontend/dist /workspace/frontend/dist
RUN chmod +x gradlew && ./gradlew --no-daemon appJar

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --gid 10001 worldcup \
    && useradd --uid 10001 --gid worldcup --no-create-home --shell /usr/sbin/nologin worldcup
WORKDIR /app
COPY --from=package --chmod=0444 /workspace/backend/build/libs/*-app.jar /app/app.jar
USER 10001:10001
ENV SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
