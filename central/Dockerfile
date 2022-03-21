FROM openjdk:8

COPY target/glowroot-central-*.zip /tmp/glowroot-central.zip
COPY docker-entrypoint.sh /usr/local/bin/
COPY glowroot-central.sh /usr/local/bin/

RUN unzip -d /usr/share /tmp/glowroot-central.zip \
    && rm /tmp/glowroot-central.zip \
    && useradd --no-log-init -r -g root glowroot \
    && chown -R glowroot:root /usr/share/glowroot-central \
    && chmod -R g+rw /usr/share/glowroot-central \
    && chmod a+x /usr/local/bin/docker-entrypoint.sh \
    && chmod a+x /usr/local/bin/glowroot-central.sh \
    && sed -i 's/^cassandra.contactPoints=$/cassandra.contactPoints=cassandra/' /usr/share/glowroot-central/glowroot-central.properties \
    && echo '\ncassandra.symmetricEncryptionKey=' >> /usr/share/glowroot-central/glowroot-central.properties

EXPOSE 4000 8181

WORKDIR /usr/share/glowroot-central

USER glowroot:glowroot

ENV GLOWROOT_OPTS -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["glowroot-central.sh"]
