FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY target/loans-api.jar app.jar

# Optional external config. The box mounts ~/apps/loans-api/config here; in the fleet cell (k8s)
# nothing is mounted and the environment is the whole override.
RUN mkdir -p /app/config

# ADDITIONAL, not SPRING_CONFIG_LOCATION: the packaged application.yml always loads, and a file
# mounted here overrides it key by key. SPRING_CONFIG_LOCATION replaced Boot's whole search list,
# so an image started with nothing mounted (every k8s pod) silently ran without the packaged file:
# no server.port, no datasource, no partner endpoints. optional: because the directory may be empty.
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/app/config/

# The packaged server.port (SERVER_PORT), which is also loans-service's port in the fleet cell.
EXPOSE 8088

# Run the application. Pinned to UTC, like the ticketing services: every timestamp is stored in UTC
# whatever the host's clock says, and the market's time (innbucks.country) is applied only where a
# person reads it.
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
