# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
ARG GLOWROOT_VERSION=0.14.8-beta.4
WORKDIR /app
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl unzip \
  && curl -fsSL -o /tmp/glowroot.zip \
       "https://github.com/glowroot/glowroot/releases/download/v${GLOWROOT_VERSION}/glowroot-${GLOWROOT_VERSION}-dist.zip" \
  && unzip -q /tmp/glowroot.zip -d /opt \
  && mv /opt/glowroot-${GLOWROOT_VERSION} /opt/glowroot \
  && mkdir -p /opt/glowroot/conf /opt/glowroot/logs /opt/glowroot/tmp \
  && rm -rf /tmp/glowroot.zip /var/lib/apt/lists/*
COPY --from=build /src/target/sample-app-0.1.0.jar /app/app.jar
ENV GLOWROOT_COLLECTOR_ADDRESS=glowroot:8181
ENTRYPOINT ["sh", "-c", "exec java \
  -javaagent:/opt/glowroot/glowroot.jar \
  -Dglowroot.agent.id=demo-sample \
  -Dglowroot.collector.address=${GLOWROOT_COLLECTOR_ADDRESS} \
  -Dglowroot.conf.dir=/opt/glowroot/conf \
  -Dglowroot.log.dir=/opt/glowroot/logs \
  -Dglowroot.tmp.dir=/opt/glowroot/tmp \
  -jar /app/app.jar"]
