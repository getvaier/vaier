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
# the `ip` binary used by VpnNetworkSetupAdapter and LanRouteAdapter, iputils-ping
# for the ICMP fallback in LanServerReachabilityService (the package ships
# /bin/ping with cap_net_raw+ep so the unprivileged ubuntu user can use it).
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 util-linux iputils-ping && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build --chown=1000:1000 /app/target/*.jar app.jar
# #359: the Vaier Android app, served from /app/android/vaier.apk. The apk/ directory always exists (it
# holds a README), so an image built from a tree with the package serves it and one built without simply
# has nothing to offer — FilesystemAndroidAppAdapter reads the absence as "no app", never as an error.
COPY --chown=1000:1000 apk/ /app/apk/
EXPOSE 8080
# The commit this image was built from: the self-update fetches the runtime files from the same one.
# Empty on a local build, which the self-update reads as "leave the runtime files alone". Last, so a
# new commit does not bust the cache of the layers above.
ARG VAIER_REVISION=
LABEL org.opencontainers.image.revision="${VAIER_REVISION}"
# Entrypoint starts as root (so setpriv can manage caps), uses cap_add: NET_ADMIN
# from compose, adds NET_ADMIN to inheritable+ambient sets (so it transfers to ip
# spawned by Java's ProcessBuilder), then drops to UID 1000 before exec'ing java.
# The Java process therefore runs as UID 1000 but can still call /bin/ip with NET_ADMIN.
ENTRYPOINT ["setpriv", "--reuid=1000", "--regid=1000", "--init-groups", "--inh-caps=+net_admin", "--ambient-caps=+net_admin", "--", "/opt/java/openjdk/bin/java", "-jar", "/app/app.jar"]
