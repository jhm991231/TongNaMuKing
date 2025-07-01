#!/bin/bash

echo "🐳 TongNaMuKing Docker Deployment Script"
echo "======================================="

# Check if Docker and Docker Compose are installed
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed. Please install Docker first."
    exit 1
fi

if ! command -v docker-compose &> /dev/null; then
    echo "❌ Docker Compose is not installed. Please install Docker Compose first."
    exit 1
fi

# Choose deployment environment
echo "Select deployment environment:"
echo "1) Development (docker-compose.yml)"
echo "2) Production (docker-compose.prod.yml)"
read -p "Enter your choice (1 or 2): " choice

case $choice in
    1)
        COMPOSE_FILE="docker-compose.yml"
        echo "🔧 Starting development deployment..."
        ;;
    2)
        COMPOSE_FILE="docker-compose.prod.yml"
        echo "🚀 Starting production deployment..."
        echo "⚠️  Make sure to set DB_PASSWORD environment variable for production!"
        ;;
    *)
        echo "❌ Invalid choice. Exiting."
        exit 1
        ;;
esac

# Stop existing containers
echo "🛑 Stopping existing containers..."
docker-compose -f $COMPOSE_FILE down

# Build and start containers
echo "🏗️  Building and starting containers..."
docker-compose -f $COMPOSE_FILE up --build -d

# Check if containers are running
echo "📊 Checking container status..."
docker-compose -f $COMPOSE_FILE ps

echo ""
echo "✅ Deployment completed!"
echo ""
echo "🌐 Services are now available at:"
echo "   - Frontend: http://localhost"
echo "   - Backend API: http://localhost:8080"
echo "   - MySQL: localhost:3306 (if exposed)"
echo ""
echo "📝 To view logs: docker-compose -f $COMPOSE_FILE logs -f"
echo "🛑 To stop: docker-compose -f $COMPOSE_FILE down"