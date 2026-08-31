# Packages a pre-built UiSandboxMain classpath (see prepare.ps1 / prepare.sh).
# Build context = demo/ only — not the whole repo.

FROM eclipse-temurin:17-jre-jammy

RUN useradd --no-log-init -r -g root glowroot

WORKDIR /opt/glowroot-demo

COPY --chown=glowroot:root runtime/classes ./classes
COPY --chown=glowroot:root runtime/test-classes ./test-classes
COPY --chown=glowroot:root runtime/lib ./lib
COPY --chown=glowroot:root docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh \
 && chmod a+x /usr/local/bin/docker-entrypoint.sh \
 && mkdir -p /opt/glowroot-demo/target \
 && chown -R glowroot:root /opt/glowroot-demo \
 && chmod -R g+rw /opt/glowroot-demo

EXPOSE 4000

USER glowroot:root

ENV JAVA_OPTS=""

ENTRYPOINT ["docker-entrypoint.sh"]
