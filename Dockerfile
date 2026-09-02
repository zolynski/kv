# One Dockerfile builds both Java services:
#
#   docker build --build-arg MODULE=kv-shard  -t kv-shard:1.0.0  .
#   docker build --build-arg MODULE=kv-router -t kv-router:1.0.0 .

# BUILD
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

COPY pom.xml .
COPY kv-core/pom.xml kv-core/
COPY kv-shard/pom.xml kv-shard/
COPY kv-router/pom.xml kv-router/
COPY kv-benchmarks/pom.xml kv-benchmarks/

RUN mvn -B -q -pl kv-shard,kv-router -am dependency:go-offline -DskipTests

COPY kv-core/src kv-core/src
COPY kv-shard/src kv-shard/src
COPY kv-router/src kv-router/src

RUN mvn -B -q -pl kv-shard,kv-router -am package -DskipTests

# RUNTIME
FROM eclipse-temurin:25-jre AS runtime
ARG MODULE
WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 1001 kv \
 && useradd --system --uid 1001 --gid kv --no-create-home kv

COPY --from=build --chown=kv:kv /build/${MODULE}/target/${MODULE}-*-exec.jar /app/application.jar

USER kv
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
