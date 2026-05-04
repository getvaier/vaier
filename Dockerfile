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
# Base image already has user `ubuntu` at UID 1000:1000 — reuse it.
# util-linux for setpriv (handles cap+user transition cleanly), iproute2 for
# the `ip` binary used by VpnNetworkSetupAdapter and LanRouteAdapter.
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 util-linux && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build --chown=1000:1000 /app/target/*.jar app.jar
EXPOSE 8080
# Entrypoint starts as root (so setpriv can manage caps), uses cap_add: NET_ADMIN
# from compose, adds NET_ADMIN to inheritable+ambient sets (so it transfers to ip
# spawned by Java's ProcessBuilder), then drops to UID 1000 before exec'ing java.
# The Java process therefore runs as UID 1000 but can still call /bin/ip with NET_ADMIN.
ENTRYPOINT ["setpriv", "--reuid=1000", "--regid=1000", "--init-groups", "--inh-caps=+net_admin", "--ambient-caps=+net_admin", "--", "/opt/java/openjdk/bin/java", "-jar", "/app/app.jar"]
