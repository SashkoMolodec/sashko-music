# 🚀 Deployment Guide

## Prerequisites
- SSH access to Ubuntu Server
- Git installed on server
- Docker or Podman installed on server

---

## 📦 Step 1: Commit and Push Changes (Local MacBook)

```bash
cd /Users/okravch/my/sm/sm

# Add changed files
git add Dockerfile
git add .env.template
git add .env.production
git add DEPLOYMENT.md

# Commit
git commit -m "Install Python CLI tools in Docker container

- Add Python3, pip, and music downloaders to Dockerfile
- Install qobuz-dl, gamdl, bandcamp-dl in sm-download-agent container
- Switch to Debian-based JRE image for Python compatibility
- Update .env.production and .env.template with container paths
- Python tools now embedded in Docker image (no host dependencies)

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>"

# Push to GitHub
git push origin main
```

---

## 🖥️ Step 2: Prepare Production Server

### 2.1 Clone repository (if first time)

```bash
ssh user@your-server-ip

mkdir -p /opt/sashko-music
cd /opt/sashko-music
git clone --recurse-submodules git@github.com:SashkoMolodec/sashko-music.git sashko-music
```

### 2.2 Pull latest changes (if already cloned)

```bash
ssh user@your-server-ip

cd /opt/sashko-music/sashko-music
git pull origin main
git submodule update --remote --merge
```

---

## 📄 Step 3: Copy Configuration Files

### 3.1 Copy .env file from local to server

```bash
# From your MacBook
scp /Users/okravch/my/sm/.env.production user@your-server-ip:/opt/sashko-music/sashko-music/.env
```

### 3.2 Copy slskd.yml file from local to server

```bash
# From your MacBook
scp /Users/okravch/my/sm/slskd/slskd.prod.yml user@your-server-ip:/opt/sashko-music/sashko-music/slskd/config/slskd.yml
```

### 3.3 Copy Apple Music cookies.txt (if using Apple Music downloads)

```bash
# From your MacBook
scp /Users/okravch/my/sm/sm-download-agent/cookies.txt user@your-server-ip:/opt/sashko-music/cookies.txt
```

### 3.4 Configure Navidrome credentials

Make sure your `.env` file has the correct Navidrome admin credentials:

```bash
# In .env file on server
NAVIDROME_USERNAME=admin
NAVIDROME_PASSWORD=your_actual_navidrome_password
```

**Note:** These credentials must match the admin user you set up in Navidrome. The library agent uses these to trigger automatic scans when new albums are added.

---

## 🏗️ Step 4: Create Directories on Server

**Note:** The `deploy.sh` script will automatically create these directories with correct permissions. If you want to do it manually:

```bash
ssh user@your-server-ip

cd /opt/sashko-music/sashko-music

# Create necessary directories in project
mkdir -p slskd/{app,downloads,incomplete,config}
mkdir -p navidrome/data

# Create downloads directories one level up
mkdir -p ../downloads/{qobuz,bandcamp,apple-music,slskd}

# Set correct permissions for Navidrome
sudo chown -R 1000:1000 navidrome/data  # Use your NAVIDROME_USER_ID:NAVIDROME_GROUP_ID from .env
```

---

## 🐳 Step 5: Deploy with Docker Compose

```bash
ssh user@your-server-ip

cd /opt/sashko-music/sashko-music
./deploy.sh
```

Or manually:

```bash
# Build images
docker compose build

# Start services
docker compose up -d

# Check status
docker compose ps

# Check logs
docker compose logs -f sm-main-agent
docker compose logs -f sm-download-agent
docker compose logs -f slskd
```

---

## ✅ Step 6: Verify Deployment

### 6.1 Check Slskd

```bash
# Check Slskd logs
docker compose logs slskd | tail -20

# Should see:
# [INFO] Connected to the Soulseek server
# [INFO] Logged in to the Soulseek server as <username>
```

### 6.2 Test Slskd API

```bash
# From server
curl -X GET http://localhost:5030/api/v0/application \
  -H "X-API-Key: <your-api-key>" | jq '.user.username'

# Should return your Soulseek username
```

### 6.3 Access Web UIs

- **Slskd**: http://your-server-ip:5030
- **Navidrome**: http://your-server-ip:4533
- **Redpanda Console**: http://your-server-ip:9094

### 6.4 Test Telegram Bot

Send `/start` to your bot in Telegram and verify it responds.

### 6.5 Test Navidrome Integration

After downloading an album through the Telegram bot:

```bash
# Check library agent logs for Navidrome scan triggers
docker compose logs sm-library-agent | grep -i navidrome

# Should see logs like:
# Triggering Navidrome scan for newly organized album: miles davis/birth of the cool (1950) [flac]
# ✓ Successfully triggered Navidrome scan for: miles davis/birth of the cool (1950) [flac]
```

**Expected behavior:**
1. Download album via Telegram bot
2. Library processes and organizes files (~30 seconds)
3. Navidrome scan automatically triggered
4. New album appears in Navidrome UI within ~30 seconds

**Note:** If Navidrome is temporarily unavailable, the system will log a warning and continue. Navidrome will automatically scan every hour anyway.

---

## 🔄 Updating After Changes

```bash
# On server
cd /opt/sashko-music/sashko-music
git pull origin main
git submodule update --remote --merge
docker compose up -d --build
```

---

## 🐛 Troubleshooting

### Slskd not connecting to Soulseek

```bash
# Check slskd.yml exists
ls -la /opt/sashko-music/sashko-music/slskd/config/slskd.yml

# Check Slskd logs
docker compose logs slskd | grep -i error
```

### API authentication failing

```bash
# Verify API key matches between:
# 1. .env file
cat /opt/sashko-music/sashko-music/.env | grep SLSKD_API_KEY

# 2. slskd.yml file
cat /opt/sashko-music/sashko-music/slskd/config/slskd.yml | grep -A 2 "api_keys"
```

### Services not starting

```bash
# Check all services
docker compose ps

# Check specific service logs
docker compose logs <service-name>

# Restart specific service
docker compose restart <service-name>
```

---

## 📝 Important Files Checklist

**In Git:**
- ✅ `docker-compose.yaml`
- ✅ `Dockerfile`
- ✅ `.gitignore`
- ✅ `.env.template`
- ✅ `slskd/slskd.template.yml`
- ✅ `deploy.sh`
- ✅ `README-DEPLOY.md`

**NOT in Git (copy manually):**
- ❌ `.env.production` → copy to server as `.env`
- ❌ `slskd/slskd.prod.yml` → copy to server as `slskd/config/slskd.yml`
- ❌ `.env.development` (local only)
- ❌ `slskd/slskd.dev.yml` (local only)

---

## 🎯 Quick Reference

| Environment | .env file | slskd.yml file | Webhook URL |
|-------------|-----------|----------------|-------------|
| **Development** | `.env.development` | `slskd/slskd.dev.yml` | `http://host.docker.internal:8081/...` |
| **Production** | `.env.production` → `.env` | `slskd/slskd.prod.yml` → `slskd/config/slskd.yml` | `http://sm-download-agent:8081/...` |

---

**Done! 🎉**
