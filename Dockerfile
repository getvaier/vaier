# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests \
    && mvn -q help:evaluate -Dexpression=project.version -DforceStdout > /app/version.txt

# Run stage
FROM eclipse-temurin:21-jre
ARG VAIER_VERSION=dev
LABEL org.opencontainers.image.version="${VAIER_VERSION}"
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
