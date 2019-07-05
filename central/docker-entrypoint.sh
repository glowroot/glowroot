#!/bin/bash
set -e

# file_env function is same as from
# https://github.com/docker-library/postgres/blob/master/11/docker-entrypoint.sh
# and https://github.com/docker-library/mariadb/blob/master/10.4/docker-entrypoint.sh
#
# usage: file_env VAR [DEFAULT]
#    ie: file_env 'XYZ_DB_PASSWORD' 'example'
# (will allow for "$XYZ_DB_PASSWORD_FILE" to fill in the value of
#  "$XYZ_DB_PASSWORD" from a file, especially for Docker's secrets feature)
file_env() {
  local var="$1"
  local fileVar="${var}_FILE"
  local def="${2:-}"
  if [ "${!var:-}" ] && [ "${!fileVar:-}" ]; then
    echo >&2 "error: both $var and $fileVar are set (but are exclusive)"
    exit 1
  fi
  local val="$def"
  if [ "${!var:-}" ]; then
    val="${!var}"
  elif [ "${!fileVar:-}" ]; then
    val="$(< "${!fileVar}")"
  fi
  export "$var"="$val"
  unset "$fileVar"
}

file_env 'CASSANDRA_PASSWORD'
file_env 'CASSANDRA_SYMMETRIC_ENCRYPTION_KEY'

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
if [ "$UI_CONTEXT_PATH" ]; then
  sed -i "s/^ui.contextPath=.*$/ui.contextPath=$UI_CONTEXT_PATH/" glowroot-central.properties
fi

exec "$@"
