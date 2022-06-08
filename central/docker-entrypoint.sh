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
file_env 'JGROUPS_SYM_ENCRYPT_KEYSTORE_PASSWORD'
file_env 'JGROUPS_SYM_ENCRYPT_KEY_PASSWORD'

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
if [ "$CASSANDRA_CONFIGURATION_FILE" ]; then
  sed -i "s/^cassandra.configurationFile=.*$/cassandra.configurationFile=$CASSANDRA_CONFIGURATION_FILE/" glowroot-central.properties
fi
if [ "$HELM_MODE" ]; then
  sed -i "s/^helmMode=.*$/helmMode=$HELM_MODE/" glowroot-central.properties
fi
if [ "$UI_CONTEXT_PATH" ]; then
  # not using "/" as sed delimiter since it is a common character used in context path
  sed -i "s|^ui.contextPath=.*$|ui.contextPath=$UI_CONTEXT_PATH|" glowroot-central.properties
fi
if [ "$CENTRAL_THREAD_POOL_MAX_SIZE" ]; then
  sed -i "s|^central.threadPoolMaxSize=.*$|central.threadPoolMaxSize=$CENTRAL_THREAD_POOL_MAX_SIZE|" glowroot-central.properties
fi
if [ "$JGROUPS_CONFIGURATION_FILE" ]; then
  sed -i "s/^jgroups.configurationFile=.*$/jgroups.configurationFile=$JGROUPS_CONFIGURATION_FILE/" glowroot-central.properties
fi
if [ "$JGROUPS_LOCAL_ADDRESS" ]; then
  sed -i "s/^jgroups.localAddress=.*$/jgroups.localAddress=$JGROUPS_LOCAL_ADDRESS/" glowroot-central.properties
fi
if [ "$JGROUPS_LOCAL_PORT" ]; then
  sed -i "s/^jgroups.localPort=.*$/jgroups.localPort=$JGROUPS_LOCAL_PORT/" glowroot-central.properties
fi
if [ "$JGROUPS_INITIAL_NODES" ]; then
  sed -i "s/^jgroups.initialNodes=.*$/jgroups.initialNodes=$JGROUPS_INITIAL_NODES/" glowroot-central.properties
fi
if [ "$JGROUPS_SYM_ENCRYPT_ALGORITHM" ]; then
  sed -i "s|^jgroups.symEncryptAlgorithm=.*$|jgroups.symEncryptAlgorithm=$JGROUPS_SYM_ENCRYPT_ALGORITHM|" glowroot-central.properties
fi
if [ "$JGROUPS_SYM_ENCRYPT_KEYSTORE_NAME" ]; then
  sed -i "s|^jgroups.symEncryptKeystoreName=.*$|jgroups.symEncryptKeystoreName=$JGROUPS_SYM_ENCRYPT_KEYSTORE_NAME|" glowroot-central.properties
fi
if [ "$JGROUPS_SYM_ENCRYPT_KEYSTORE_PASSWORD" ]; then
  sed -i "s/^jgroups.symEncryptKeystorePassword=.*$/jgroups.symEncryptKeystorePassword=$JGROUPS_SYM_ENCRYPT_KEYSTORE_PASSWORD/" glowroot-central.properties
fi
if [ "$JGROUPS_SYM_ENCRYPT_KEY_ALIAS" ]; then
  sed -i "s/^jgroups.symEncryptKeyAlias=.*$/jgroups.symEncryptKeyAlias=$JGROUPS_SYM_ENCRYPT_KEY_ALIAS/" glowroot-central.properties
fi
if [ "$JGROUPS_SYM_ENCRYPT_KEY_PASSWORD" ]; then
  sed -i "s/^jgroups.symEncryptKeyPassword=.*$/jgroups.symEncryptKeyPassword=$JGROUPS_SYM_ENCRYPT_KEY_PASSWORD/" glowroot-central.properties
fi

exec "$@"
