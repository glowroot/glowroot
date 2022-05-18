FROM openjdk:17-jdk-bullseye as builder

COPY target/glowroot-central-*.zip /tmp/glowroot-central.zip
RUN useradd --no-log-init -r -g root glowroot
RUN unzip -d /tmp /tmp/glowroot-central.zip \
    && chown -R glowroot:root /tmp/glowroot-central \
    && chmod -R g+rw /tmp/glowroot-central

FROM openjdk:17-jdk-bullseye

COPY docker-entrypoint.sh /usr/local/bin/
COPY glowroot-central.sh /usr/local/bin/

RUN useradd --no-log-init -r -g root glowroot
COPY --from=builder --chown=glowroot:root /tmp/glowroot-central /usr/share/glowroot-central
USER root
RUN chmod a+x /usr/local/bin/docker-entrypoint.sh \
    && chmod a+x /usr/local/bin/glowroot-central.sh \
    && sed -i 's/^cassandra.contactPoints=$/cassandra.contactPoints=cassandra/' /usr/share/glowroot-central/glowroot-central.properties \
    && echo '\ncassandra.symmetricEncryptionKey=' >> /usr/share/glowroot-central/glowroot-central.properties

EXPOSE 4000 8181

WORKDIR /usr/share/glowroot-central

USER glowroot:root

ENV GLOWROOT_OPTS ""

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["glowroot-central.sh"]
