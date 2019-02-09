#!/bin/bash
set -e

exec java $GLOWROOT_OPTS -jar glowroot-central.jar
