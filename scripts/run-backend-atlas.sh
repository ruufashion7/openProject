#!/usr/bin/env bash
# Run backend with TLS settings suitable for MongoDB Atlas
set -e
cd "$(dirname "$0")/.."
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"
echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"
exec mvn -q spring-boot:run
