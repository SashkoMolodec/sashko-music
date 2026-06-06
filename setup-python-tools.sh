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
echo ""

if command -v pipx &> /dev/null; then
    echo "✓ pipx found - using pipx for installation (recommended)"
    INSTALL_METHOD="pipx"
    INSTALL_CMD="pipx install"
elif command -v pip3 &> /dev/null; then
    echo "✓ pip3 found - using pip3 with --user flag"
    INSTALL_METHOD="pip3"
    INSTALL_CMD="pip3 install --user"
else
    echo "❌ Error: Neither pipx nor pip3 is installed"
    echo "Please install one of them:"
    echo "  For pipx (recommended): sudo apt install pipx -y && pipx ensurepath"
    echo "  For pip3: sudo apt install python3-pip -y"
    exit 1
fi

echo ""
echo "📦 Installing Python CLI tools using $INSTALL_METHOD..."
echo ""

install_tool() {
    local name=$1
    local package=$2
    echo "Installing $name ($package)..."
    if ! $INSTALL_CMD "$package" 2>&1; then
        pip3 install --user --break-system-packages "$package" || {
            echo "⚠️  Warning: $name installation failed"
        }
    else
        echo "✓ $name installed successfully"
    fi
    echo ""
}

install_tool "streamrip (Qobuz downloader)" "streamrip"
install_tool "gamdl (Apple Music downloader)" "gamdl"
install_tool "bandcamp-dl (Bandcamp downloader)" "bandcamp-downloader"
install_tool "yt-dlp (YouTube Music downloader)" "yt-dlp"

echo "========================================="
echo "  INSTALLATION COMPLETE"
echo "========================================="
echo ""

TOOLS_PATH="$HOME/.local/bin"

if [[ ":$PATH:" != *":$TOOLS_PATH:"* ]]; then
    echo "⚠️  WARNING: $TOOLS_PATH is not in your PATH"
    echo ""
    echo "Add this line to your ~/.bashrc or ~/.zshrc:"
    echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
    echo ""

    if [ -f "$HOME/.bashrc" ]; then
        if ! grep -q 'export PATH="$HOME/.local/bin:$PATH"' "$HOME/.bashrc"; then
            echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$HOME/.bashrc"
            echo "✓ Added PATH to ~/.bashrc automatically"
            echo ""
        fi
    fi
fi

export PATH="$HOME/.local/bin:$PATH"

echo "Verifying installations:"
echo ""

for cmd in rip gamdl bandcamp-dl yt-dlp; do
    path=$(command -v "$cmd" 2>/dev/null || echo "")
    if [ -n "$path" ]; then
        echo "✓ $cmd: $path"
    else
        echo "❌ $cmd not found in PATH (reload shell and retry)"
    fi
done

echo ""
echo "========================================="
echo "  UPDATE YOUR .env FILE"
echo "========================================="
echo ""
echo "QOBUZ_CLI_PATH=$(command -v rip 2>/dev/null || echo "$TOOLS_PATH/rip")"
echo "APPLE_GAMDL_PATH=$(command -v gamdl 2>/dev/null || echo "$TOOLS_PATH/gamdl")"
echo "BANDCAMP_CLI_PATH=$(command -v bandcamp-dl 2>/dev/null || echo "$TOOLS_PATH/bandcamp-dl")"
echo "YTDLP_CLI_PATH=$(command -v yt-dlp 2>/dev/null || echo "$TOOLS_PATH/yt-dlp")"
echo ""
