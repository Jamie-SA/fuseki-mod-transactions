#!/bin/sh
# Replaces the base Jena Fuseki image's own entrypoint.sh (which launches
# with `java -jar`, silently ignoring any classpath additions). This launches
# the same fat jar with an explicit classpath instead, so that a
# FusekiAutoModule jar dropped into /fuseki/modules/ is discovered via
# META-INF/services at startup. See DOCKER.md for the full explanation.
exec "$JAVA_HOME/bin/java" $JAVA_OPTIONS \
    -cp "${FUSEKI_DIR}/${FUSEKI_JAR}:${FUSEKI_DIR}/modules/*" \
    org.apache.jena.fuseki.main.cmds.FusekiServerCmd "$@"
