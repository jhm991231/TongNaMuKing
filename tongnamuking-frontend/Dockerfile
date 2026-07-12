# Build stage
FROM node:18 AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install dependencies
RUN npm install

# Copy source code
COPY . .

# Build the application
# 로컬 docker-compose 전용 빌드: development 모드로 .env(localhost:8080)를 사용하게 하여
# .env.production의 Railway 주소가 박히지 않도록 한다. (프로덕션 배포는 루트 Dockerfile을 사용)
RUN npm run build -- --mode development

# Production stage
FROM nginx:alpine

# Copy built files to nginx
COPY --from=builder /app/dist /usr/share/nginx/html

# Copy nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Expose port 80
EXPOSE 80

# Start nginx
CMD ["nginx", "-g", "daemon off;"]