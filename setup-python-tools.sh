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

# Check if pipx is available (recommended for Ubuntu 24.04+)
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

echo "1/3 Installing qobuz-dl (Qobuz downloader)..."
if $INSTALL_CMD qobuz-dl 2>&1 | grep -q "error: externally-managed-environment"; then
    echo "⚠️  Attempting with --break-system-packages flag..."
    pip3 install --user --break-system-packages qobuz-dl || {
        echo "⚠️  Warning: qobuz-dl installation failed"
    }
else
    echo "✓ qobuz-dl installed successfully"
fi
echo ""

echo "2/3 Installing gamdl (Apple Music downloader)..."
if $INSTALL_CMD gamdl 2>&1 | grep -q "error: externally-managed-environment"; then
    echo "⚠️  Attempting with --break-system-packages flag..."
    pip3 install --user --break-system-packages gamdl || {
        echo "⚠️  Warning: gamdl installation failed"
    }
else
    echo "✓ gamdl installed successfully"
fi
echo ""

echo "3/3 Installing bandcamp-downloader (Bandcamp downloader)..."
if $INSTALL_CMD bandcamp-downloader 2>&1 | grep -q "error: externally-managed-environment"; then
    echo "⚠️  Attempting with --break-system-packages flag..."
    pip3 install --user --break-system-packages bandcamp-downloader || {
        echo "⚠️  Warning: bandcamp-downloader installation failed"
    }
else
    echo "✓ bandcamp-downloader installed successfully"
fi
echo ""

echo "========================================="
echo "  INSTALLATION COMPLETE"
echo "========================================="
echo ""

# Determine tools path based on installation method
if [ "$INSTALL_METHOD" = "pipx" ]; then
    TOOLS_PATH="$HOME/.local/bin"
else
    TOOLS_PATH="$HOME/.local/bin"
fi

echo "Tools are installed in: $TOOLS_PATH"
echo ""

# Update PATH if needed
if [[ ":$PATH:" != *":$TOOLS_PATH:"* ]]; then
    echo "⚠️  WARNING: $TOOLS_PATH is not in your PATH"
    echo ""
    echo "Add this line to your ~/.bashrc or ~/.zshrc:"
    echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
    echo ""
    echo "Then reload your shell:"
    echo "  source ~/.bashrc"
    echo ""

    # Auto-add to bashrc if it exists
    if [ -f "$HOME/.bashrc" ]; then
        if ! grep -q 'export PATH="$HOME/.local/bin:$PATH"' "$HOME/.bashrc"; then
            echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$HOME/.bashrc"
            echo "✓ Added PATH to ~/.bashrc automatically"
            echo ""
        fi
    fi
fi

# Source bashrc to update PATH in current session
if [ -f "$HOME/.bashrc" ]; then
    export PATH="$HOME/.local/bin:$PATH"
fi

echo "Verifying installations:"
echo ""

QOBUZ_PATH=$(command -v qobuz-dl 2>/dev/null || echo "")
GAMDL_PATH=$(command -v gamdl 2>/dev/null || echo "")
BANDCAMP_PATH=$(command -v bandcamp-dl 2>/dev/null || echo "")

if [ -n "$QOBUZ_PATH" ]; then
    echo "✓ qobuz-dl: $QOBUZ_PATH"
else
    echo "❌ qobuz-dl not found in PATH"
    QOBUZ_PATH="$TOOLS_PATH/qobuz-dl"
fi

if [ -n "$GAMDL_PATH" ]; then
    echo "✓ gamdl: $GAMDL_PATH"
else
    echo "❌ gamdl not found in PATH"
    GAMDL_PATH="$TOOLS_PATH/gamdl"
fi

if [ -n "$BANDCAMP_PATH" ]; then
    echo "✓ bandcamp-dl: $BANDCAMP_PATH"
else
    echo "❌ bandcamp-dl not found in PATH"
    BANDCAMP_PATH="$TOOLS_PATH/bandcamp-dl"
fi

echo ""
echo "========================================="
echo "  UPDATE YOUR .env FILE"
echo "========================================="
echo ""
echo "Add these lines to your .env file:"
echo ""
echo "QOBUZ_CLI_PATH=$QOBUZ_PATH"
echo "APPLE_MUSIC_CLI_PATH=$GAMDL_PATH"
echo "BANDCAMP_CLI_PATH=$BANDCAMP_PATH"
echo ""
echo "If tools are not found, reload your shell and try again:"
echo "  source ~/.bashrc"
echo ""
