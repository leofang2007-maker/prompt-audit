# Single self-contained image: builds the React SPA, bakes it into the Spring Boot jar's
# static resources, and runs ONE process that serves both the SPA (/) and the API (/api).
# Build context = repo ROOT (so it can see ../web):  docker build -f server/Dockerfile .

# Stage 1 — build the SPA (static files).
FROM node:20-alpine AS web
WORKDIR /web
COPY web/package.json ./
RUN npm install
COPY web/tsconfig.json web/vite.config.ts web/index.html ./
COPY web/public ./public
COPY web/src ./src
RUN npm run build          # → /web/dist

# Stage 2 — build the Spring Boot jar with the SPA baked into src/main/resources/static.
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /app
COPY server/pom.xml ./
RUN mvn -q -e -B dependency:go-offline
COPY server/src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -q -e -B clean package -DskipTests

# Stage 3 — runtime.
FROM eclipse-temurin:8-jre
WORKDIR /app
ENV LANG=C.UTF-8
COPY --from=build /app/target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
