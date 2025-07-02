# Build stage
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy gradle files
COPY build.gradle settings.gradle ./
COPY gradlew gradlew.bat ./
COPY gradle gradle

# Copy source code
COPY src src

# Build the application
RUN gradle build -x test

# Production stage
FROM openjdk:21-jdk-slim

WORKDIR /app

# Install Node.js for chat collector
RUN apt-get update && apt-get install -y curl && \
    curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy built jar file
COPY --from=builder /app/build/libs/*.jar app.jar

# Create chat-collector directory (files will be mounted via volume)
RUN mkdir -p /app/chat-collector

# Expose port 8080
EXPOSE 8080

# Run the application
CMD ["java", "-jar", "app.jar"]