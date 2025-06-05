FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/loans-embedded-ui-*.jar app.jar

# Create directory for external config
RUN mkdir -p /app/config

# Set environment variables
ENV SPRING_CONFIG_LOCATION=file:/app/config/

# Expose the port the app runs on
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]