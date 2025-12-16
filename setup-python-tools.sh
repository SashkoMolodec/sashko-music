#!/bin/bash

set -e

echo "========================================="
echo "  PYTHON CLI TOOLS INSTALLATION"
echo "========================================="
echo ""

if ! command -v python3 &> /dev/null; then
    echo "❌ Error: Python 3 is not installed"
    echo "Please install Python 3 first:"
    echo "  sudo apt update"
    echo "  sudo apt install python3 python3-pip python3-venv -y"
    exit 1
fi

PYTHON_VERSION=$(python3 --version)
echo "✓ Python found: $PYTHON_VERSION"

if ! command -v pip3 &> /dev/null; then
    echo "❌ Error: pip3 is not installed"
    echo "Please install pip:"
    echo "  sudo apt install python3-pip -y"
    exit 1
fi

echo "✓ pip3 found"
echo ""

echo "📦 Installing Python CLI tools..."
echo ""

echo "1/3 Installing qobuz-dl (Qobuz downloader)..."
pip3 install --user qobuz-dl || {
    echo "⚠️  Warning: qobuz-dl installation failed"
}
echo ""

echo "2/3 Installing gamdl (Apple Music downloader)..."
pip3 install --user gamdl || {
    echo "⚠️  Warning: gamdl installation failed"
}
echo ""

echo "3/3 Installing bandcamp-dl (Bandcamp downloader)..."
pip3 install --user bandcamp-dl || {
    echo "⚠️  Warning: bandcamp-dl installation failed"
}
echo ""

echo "========================================="
echo "  INSTALLATION COMPLETE"
echo "========================================="
echo ""

TOOLS_PATH="$HOME/.local/bin"
echo "Tools are installed in: $TOOLS_PATH"
echo ""

if [[ ":$PATH:" != *":$TOOLS_PATH:"* ]]; then
    echo "⚠️  WARNING: $TOOLS_PATH is not in your PATH"
    echo ""
    echo "Add this line to your ~/.bashrc or ~/.zshrc:"
    echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
    echo ""
    echo "Then reload your shell:"
    echo "  source ~/.bashrc"
    echo ""
fi

echo "Verifying installations:"
echo ""

if command -v qobuz-dl &> /dev/null; then
    echo "✓ qobuz-dl: $(which qobuz-dl)"
else
    echo "❌ qobuz-dl not found in PATH"
fi

if command -v gamdl &> /dev/null; then
    echo "✓ gamdl: $(which gamdl)"
else
    echo "❌ gamdl not found in PATH"
fi

if command -v bandcamp-dl &> /dev/null; then
    echo "✓ bandcamp-dl: $(which bandcamp-dl)"
else
    echo "❌ bandcamp-dl not found in PATH"
fi

echo ""
echo "Update your .env file with the correct paths:"
echo "  QOBUZ_CLI_PATH=$(which qobuz-dl 2>/dev/null || echo '/home/username/.local/bin/qobuz-dl')"
echo "  APPLE_MUSIC_CLI_PATH=$(which gamdl 2>/dev/null || echo '/home/username/.local/bin/gamdl')"
echo "  BANDCAMP_CLI_PATH=$(which bandcamp-dl 2>/dev/null || echo '/home/username/.local/bin/bandcamp-dl')"
echo ""