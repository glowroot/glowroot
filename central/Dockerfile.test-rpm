# this Dockerfile exists just for testing the glowroot central rpm

FROM centos/systemd

COPY target/glowroot-central-*.rpm /tmp/rpm/

RUN yum install -y $(ls -1 /tmp/rpm/*.rpm) \
    && rm -rf /tmp/rpm \
    && sed -i 's/^cassandra.contactPoints=$/cassandra.contactPoints=cassandra/' /etc/glowroot-central/glowroot-central.properties \
    && systemctl enable glowroot-central.service

EXPOSE 4000 8181

CMD /usr/sbin/init

# example of how to test the glowroot central rpm using this Dockerfile:
#
# docker build -f Dockerfile.test-rpm -t glowroot/glowroot-central/test-rpm .
# docker run --name myglowroot --link mycassandra:cassandra --privileged -v /sys/fs/cgroup:/sys/fs/cgroup:ro -p 4000:4000 -p 8181:8181 -d glowroot/glowroot-central/test-rpm
