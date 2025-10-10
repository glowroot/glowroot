FROM eclipse-temurin:21.0.8_9-jdk-noble AS builder

COPY target/glowroot-central-*.zip /tmp/glowroot-central.zip
RUN useradd --no-log-init -r -g root glowroot
RUN apt update && apt install -y unzip
RUN unzip -d /tmp /tmp/glowroot-central.zip \
    && chown -R glowroot:root /tmp/glowroot-central \
    && chmod -R g+rw /tmp/glowroot-central

FROM eclipse-temurin:21.0.8_9-jdk-noble

COPY docker-entrypoint.sh /usr/local/bin/
COPY glowroot-central.sh /usr/local/bin/

RUN useradd --no-log-init -r -g root glowroot

COPY --from=builder --chown=glowroot:root /tmp/glowroot-central /usr/share/glowroot-central

WORKDIR /usr/share/glowroot-central

# need to give group write permission because OpenShift will ignore USER directives
# and run the POD as a numbered userid which cannot possibly exist on the underlying physical host
# but which is guaranteed to be a member of the "root" group
RUN chmod g+w /usr/share/glowroot-central \
    && chmod a+x /usr/local/bin/docker-entrypoint.sh \
    && chmod a+x /usr/local/bin/glowroot-central.sh

EXPOSE 4000 8181

USER glowroot:root

ENV GLOWROOT_OPTS=""

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["glowroot-central.sh"]
