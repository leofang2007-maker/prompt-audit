# Multi-stage build → self-contained Spring Boot fat jar. No internal Nexus needed (public Maven Central).
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -e -B dependency:go-offline
COPY src ./src
RUN mvn -q -e -B clean package -DskipTests

FROM eclipse-temurin:8-jre
WORKDIR /app
ENV LANG=C.UTF-8
COPY --from=build /app/target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
