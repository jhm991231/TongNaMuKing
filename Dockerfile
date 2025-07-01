# Multi-stage build for production deployment
FROM node:18 AS frontend-builder
WORKDIR /app/frontend
COPY tongnamuking-frontend/package*.json ./
RUN npm install
COPY tongnamuking-frontend/ ./
RUN npm run build

# Backend builder
FROM gradle:8.5-jdk21 AS backend-builder
WORKDIR /app/backend
COPY tongnamuking-backend/build.gradle tongnamuking-backend/settings.gradle ./
COPY tongnamuking-backend/gradlew tongnamuking-backend/gradlew.bat ./
COPY tongnamuking-backend/gradle gradle
COPY tongnamuking-backend/src src
RUN gradle build -x test

# Production stage
FROM openjdk:21-jdk-slim
WORKDIR /app

# Install Node.js and nginx
RUN apt-get update && apt-get install -y curl nginx && \
    curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy backend jar
COPY --from=backend-builder /app/backend/build/libs/*.jar backend.jar

# Copy frontend build
COPY --from=frontend-builder /app/frontend/dist /var/www/html

# Copy chat collector
COPY chat-collector /app/chat-collector

# Copy nginx config
COPY nginx.conf /etc/nginx/sites-available/default

# Expose port
EXPOSE 80

# Start script
COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh

CMD ["/app/start.sh"]