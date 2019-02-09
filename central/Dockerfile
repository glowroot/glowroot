FROM openjdk:8

ENV GLOWROOT_OPTS="-XX:+PrintFlagsFinal -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap"

COPY target/glowroot-central-*.zip /tmp/glowroot-central.zip

RUN unzip -d /usr/share /tmp/glowroot-central.zip \
    && rm /tmp/glowroot-central.zip \
    && sed -i 's/^cassandra.contactPoints=$/cassandra.contactPoints=cassandra/' /usr/share/glowroot-central/glowroot-central.properties \
    && echo '\ncassandra.symmetricEncryptionKey=' >> /usr/share/glowroot-central/glowroot-central.properties \
    && groupadd -r glowroot \
    && useradd --no-log-init -r -g glowroot glowroot \
    && chown -R glowroot:glowroot /usr/share/glowroot-central

EXPOSE 4000 8181

COPY docker-entrypoint.sh /usr/local/bin/
COPY start-glowroot.sh /usr/local/bin/

WORKDIR /usr/share/glowroot-central

USER glowroot:glowroot

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["/usr/local/bin/start-glowroot.sh"]

