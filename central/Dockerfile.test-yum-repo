# this Dockerfile exists just for testing the glowroot central yum repository

FROM centos/systemd

RUN yum-config-manager --add-repo https://glowroot.org/yum/glowroot.repo \
    && yum -y install glowroot-central \
    && sed -i 's/^cassandra.contactPoints=$/cassandra.contactPoints=cassandra/' /etc/glowroot-central/glowroot-central.properties \
    && systemctl enable glowroot-central.service

EXPOSE 4000 8181

CMD /usr/sbin/init

# example of how to test the glowroot central yum repository using this Dockerfile:
#
# docker build -f Dockerfile.test-yum-repo -t glowroot/glowroot-central/test-yum-repo .
# docker run --name myglowroot --link mycassandra:cassandra --privileged -v /sys/fs/cgroup:/sys/fs/cgroup:ro -p 4000:4000 -p 8181:8181 -d glowroot/glowroot-central/test-yum-repo
