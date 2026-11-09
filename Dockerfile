# Build the verified JAR first: mvn -B clean verify
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir -p /app/data && chown -R 10001:10001 /app
COPY --chown=10001:10001 --chmod=0444 server/target/evacuation-sim-0.3.0.jar /app/evacuation-sim.jar
USER 10001:10001
ENV HOST=0.0.0.0 PORT=8080 DB_PATH=/app/data/evac.db
EXPOSE 8080
VOLUME ["/app/data"]
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-jar", "/app/evacuation-sim.jar"]
