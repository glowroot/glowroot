FROM openjdk:8

COPY target/glowroot-central-*.zip /tmp/glowroot-central.zip

RUN unzip -d /usr/share /tmp/glowroot-central.zip \
    && rm /tmp/glowroot-central.zip

EXPOSE 4000 8181

COPY docker-entrypoint.sh /usr/local/bin/

WORKDIR /usr/share/glowroot-central

ENTRYPOINT ["docker-entrypoint.sh"]

CMD ["java", "-jar", "glowroot-central.jar"]
