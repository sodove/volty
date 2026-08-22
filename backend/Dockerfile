FROM gradle:8.13-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean installDist

FROM busybox:1.37.0-musl AS healthcheck

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/install/volty-backend/ /app/
COPY --from=healthcheck /bin/busybox /bin/busybox
ENV PORT=8080
EXPOSE 8080
USER 10001
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD ["/bin/busybox", "wget", "-q", "-O", "-", "http://127.0.0.1:8080/health"]
ENTRYPOINT ["/app/bin/volty-backend"]
