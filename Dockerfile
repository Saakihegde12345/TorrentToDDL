FROM gradle:8.5-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle -PdesktopOnly=true :desktop:headlessDistribution --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /opt/torrenttoddl
COPY --from=build /workspace/desktop/build/headless/ ./
RUN sed -i 's/\r$//' run-server.sh && chmod +x run-server.sh && mkdir /data && chown 10001:10001 /data
USER 10001:10001
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["./run-server.sh"]
