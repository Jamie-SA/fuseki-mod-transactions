FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM fuseki:latest
USER root
RUN mkdir -p /fuseki/modules \
    && chown fuseki:fuseki /fuseki/modules
COPY --from=build /build/target/fuseki-mod-transactions-*.jar /fuseki/modules/
COPY entrypoint.sh /fuseki/entrypoint.sh
RUN chmod +x /fuseki/entrypoint.sh && chown fuseki:fuseki /fuseki/entrypoint.sh
USER fuseki
