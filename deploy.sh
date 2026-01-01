#!/bin/bash

set -e

echo "========================================="
echo "  SASHKO MUSIC - DEPLOYMENT SCRIPT"
echo "========================================="
echo ""

# Setup SSH agent to cache passphrase
setup_ssh_agent() {
    # Check if ssh-agent is already running
    if [ -z "$SSH_AUTH_SOCK" ] || ! ssh-add -l &>/dev/null; then
        echo "🔑 Starting SSH agent and adding key..."
        eval "$(ssh-agent -s)" > /dev/null

        # Find the SSH key (try common paths)
        SSH_KEY=""
        if [ -f "$HOME/.ssh/id_ed25519" ]; then
            SSH_KEY="$HOME/.ssh/id_ed25519"
        elif [ -f "$HOME/.ssh/id_rsa" ]; then
            SSH_KEY="$HOME/.ssh/id_rsa"
        fi

        if [ -n "$SSH_KEY" ]; then
            ssh-add "$SSH_KEY" || {
                echo "⚠️  Warning: Failed to add SSH key. You may need to enter passphrase multiple times."
            }
        else
            echo "⚠️  Warning: No SSH key found. Skipping ssh-agent setup."
        fi
    else
        echo "✓ SSH agent already running with loaded keys"
    fi
    echo ""
}

# Run SSH agent setup
setup_ssh_agent


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

echo "📁 Creating directories..."

# Create necessary directories
mkdir -p slskd/{app,downloads,incomplete,config}
mkdir -p downloads/{qobuz,bandcamp,apple-music,slskd/incomplete}

echo "✓ Directories created"
echo ""

echo "📥 Pulling latest code from GitHub..."
git pull origin main || {
    echo "⚠️  Warning: git pull failed for main repo"
}

echo "📥 Updating submodules (sm-main-agent, sm-library-agent, sm-download-agent, sm-api)..."
git submodule update --remote --merge || {
    echo "⚠️  Warning: submodule update failed, continuing with current code"
}
echo ""

echo "📥 Updating sm-audio-analyzer..."
if [ -d "sm-audio-analyzer/.git" ]; then
    echo "  Pulling latest changes..."
    (cd sm-audio-analyzer && git pull origin main) || {
        echo "⚠️  Warning: Failed to pull sm-audio-analyzer, continuing with current code"
    }
else
    echo "  Cloning sm-audio-analyzer repository..."
    git clone git@github.com:SashkoMolodec/sm-audio-analyzer.git sm-audio-analyzer || {
        echo "⚠️  Warning: Failed to clone sm-audio-analyzer, continuing without it"
    }
fi
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

echo "👤 Creating Navidrome admin user (if first time)..."
# Wait a bit more for Navidrome to be fully ready
sleep 5

# Try to create admin user via API (only works if no users exist)
if command -v curl &> /dev/null; then
    NAVIDROME_URL="http://localhost:4533"

    # Read credentials from .env (safely, without sourcing)
    if [ -f .env ]; then
        NAVIDROME_USERNAME=$(grep "^NAVIDROME_USERNAME=" .env | cut -d '=' -f2)
        NAVIDROME_PASSWORD=$(grep "^NAVIDROME_PASSWORD=" .env | cut -d '=' -f2)

        # Create admin user using credentials from .env
        curl -s -X POST \
            -H "Content-Type: application/json" \
            "${NAVIDROME_URL}/auth/createAdmin" \
            --data "{\"username\":\"${NAVIDROME_USERNAME}\",\"password\":\"${NAVIDROME_PASSWORD}\"}" \
            > /dev/null 2>&1 && \
            echo "✓ Navidrome admin user created: ${NAVIDROME_USERNAME}" || \
            echo "✓ Navidrome admin user already exists (or service not ready yet)"
    else
        echo "⚠️  .env file not found, skipping Navidrome admin creation"
    fi
else
    echo "⚠️  curl not found, skipping Navidrome admin creation"
    echo "   Create admin manually at: http://localhost:4533"
fi
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
echo "  • API:                        http://localhost:8083"
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