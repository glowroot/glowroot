#!/bin/bash
set -e

if [ "$CASSANDRA_CONTACT_POINTS" ]; then
  sed -i "s/^cassandra.contactPoints=.*$/cassandra.contactPoints=$CASSANDRA_CONTACT_POINTS/" glowroot-central.properties
fi
if [ "$CASSANDRA_USERNAME" ]; then
  sed -i "s/^cassandra.username=.*$/cassandra.username=$CASSANDRA_USERNAME/" glowroot-central.properties
fi
if [ "$CASSANDRA_PASSWORD" ]; then
  sed -i "s/^cassandra.password=.*$/cassandra.password=$CASSANDRA_PASSWORD/" glowroot-central.properties
fi
if [ "$CASSANDRA_KEYSPACE" ]; then
  sed -i "s/^cassandra.keyspace=.*$/cassandra.keyspace=$CASSANDRA_KEYSPACE/" glowroot-central.properties
fi
if [ "$CASSANDRA_CONSISTENCY_LEVEL" ]; then
  sed -i "s/^cassandra.consistencyLevel=.*$/cassandra.consistencyLevel=$CASSANDRA_CONSISTENCY_LEVEL/" glowroot-central.properties
fi
if [ "$CASSANDRA_SYMMETRIC_ENCRYPTION_KEY" ]; then
  sed -i "s/^cassandra.symmetricEncryptionKey=.*$/cassandra.symmetricEncryptionKey=$CASSANDRA_SYMMETRIC_ENCRYPTION_KEY/" glowroot-central.properties
fi

exec "$@"
