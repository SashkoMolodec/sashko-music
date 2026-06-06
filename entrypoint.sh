#!/bin/bash
set -e

CONFIG_DIR="/root/.config/streamrip"
CONFIG_FILE="$CONFIG_DIR/config.toml"

mkdir -p "$CONFIG_DIR"

if [ ! -f "$CONFIG_FILE" ]; then
    rip config reset
fi

if [ -n "$QOBUZ_AUTH_TOKEN" ] && [ -n "$QOBUZ_EMAIL" ]; then
    python3 - <<'EOF'
import os, re

path = '/root/.config/streamrip/config.toml'
with open(path, 'r') as f:
    content = f.read()

email = os.environ['QOBUZ_EMAIL']
token = os.environ['QOBUZ_AUTH_TOKEN']

content = re.sub(r'use_auth_token\s*=\s*\S+', 'use_auth_token = true', content)
content = re.sub(r'email_or_userid\s*=\s*"[^"]*"', f'email_or_userid = "{email}"', content)
content = re.sub(r'password_or_token\s*=\s*"[^"]*"', f'password_or_token = "{token}"', content)

with open(path, 'w') as f:
    f.write(content)

print(f'Streamrip config patched: user={email}')
EOF
fi

exec java -jar /app/app.jar
