#!/bin/bash

set -e

echo "========================================="
echo "  SASHKO MUSIC - DEPLOYMENT SCRIPT"
echo "========================================="
echo ""

COMPOSE_CMD=""
if command -v docker &> /dev/null && docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
    echo "✓ Using Docker Compose"
elif command -v podman-compose &> /dev/null; then
    COMPOSE_CMD="podman-compose"
    echo "✓ Using Podman Compose"
elif command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
    echo "✓ Using docker-compose (legacy)"
else
    echo "❌ Error: Neither docker compose, podman-compose, nor docker-compose found"
    echo "Please install Docker/Podman and compose tool"
    exit 1
fi

if [ ! -f .env ]; then
    echo "❌ Error: .env file not found"
    echo "Please create .env file from .env.template:"
    echo "  cp .env.template .env"
    echo "  nano .env  # and fill in your values"
    exit 1
fi

echo "✓ Environment file found"
echo ""

echo "📥 Pulling latest code from GitHub..."
git pull origin main || {
    echo "⚠️  Warning: git pull failed for main repo"
}

echo "📥 Updating submodules (sm-main-agent, sm-library-agent, sm-download-agent)..."
git submodule update --remote --merge || {
    echo "⚠️  Warning: submodule update failed, continuing with current code"
}
echo ""

echo "🔨 Building Docker images..."
echo "This will build all three services (may take a few minutes)..."
$COMPOSE_CMD build
echo ""

echo "🚀 Starting services..."
$COMPOSE_CMD up -d
echo ""

echo "⏳ Waiting for services to start..."
sleep 10
echo ""

echo "📊 Service Status:"
$COMPOSE_CMD ps
echo ""

echo "========================================="
echo "  DEPLOYMENT COMPLETE"
echo "========================================="
echo ""
echo "Services are running:"
echo "  • Main Agent (Telegram Bot):  http://localhost:8080"
echo "  • Library Agent:              http://localhost:8082"
echo "  • Download Agent:             http://localhost:8081"
echo "  • Navidrome (Music Server):   http://localhost:4533"
echo "  • Slskd (Soulseek):           http://localhost:5030"
echo "  • Redpanda Console (Kafka):   http://localhost:9094"
echo "  • PostgreSQL:                 localhost:5432"
echo ""
echo "Useful commands:"
echo "  • View logs:           $COMPOSE_CMD logs -f [service_name]"
echo "  • Restart service:     $COMPOSE_CMD restart [service_name]"
echo "  • Stop all services:   $COMPOSE_CMD down"
echo "  • View service status: $COMPOSE_CMD ps"
echo ""