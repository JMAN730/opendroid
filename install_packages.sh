#!/bin/bash
# install_packages.sh
# This script installs JDK 21, Android SDK Command-line tools, and the necessary Android SDK packages
# to build OpenDroid on Debian/Ubuntu systems.

set -e

echo "================================================"
echo "  OpenDroid Build Dependency Installer for Debian"
echo "================================================"

# 1. Update and install JDK 21 & essential tools
echo "[1/5] Installing OpenJDK 21 and dependencies..."
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk wget unzip curl git

# Detect JDK 21 installation path to prevent unsupported class version errors if default Java is newer
JDK_21_PATH=""
for path in /usr/lib/jvm/java-21-openjdk-* /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-1.21.0-openjdk-*; do
    if [ -d "$path/bin" ]; then
        JDK_21_PATH="$path"
        break
    fi
done

if [ -n "$JDK_21_PATH" ]; then
    echo "Using JDK 21 at: $JDK_21_PATH"
    export JAVA_HOME="$JDK_21_PATH"
    export PATH="$JAVA_HOME/bin:$PATH"
else
    echo "Warning: JDK 21 path could not be auto-detected."
fi

# 2. Define Android SDK installation path
ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"

echo "[2/5] Downloading Android Command Line Tools..."
TEMP_ZIP=$(mktemp)
# Stable download link for Android cmdline-tools (version 11076708)
wget -O "$TEMP_ZIP" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "Extracting tools..."
unzip -q "$TEMP_ZIP" -d "$ANDROID_HOME/cmdline-tools"
rm -f "$TEMP_ZIP"

# Note: The zip extracts to 'cmdline-tools', but it needs to be inside 'latest' for sdkmanager to work
if [ -d "$ANDROID_HOME/cmdline-tools/latest" ]; then
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
fi
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"

# Export variables for the current script execution session
export ANDROID_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 3. Accept Licenses & install target SDK platforms
echo "[3/5] Installing Android SDK platforms & build-tools (API 35)..."
# Accept all SDK licenses automatically
yes | sdkmanager --licenses

# Install platforms, build-tools, and platform-tools
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 4. Configure environment variables in .bashrc if not already present
echo "[4/5] Configuring environment variables..."
BASHRC="$HOME/.bashrc"

# Configure JAVA_HOME
if ! grep -q "JAVA_HOME" "$BASHRC"; then
    echo "" >> "$BASHRC"
    echo "# Java 21 environment variables" >> "$BASHRC"
    if [ -n "$JDK_21_PATH" ]; then
        echo "export JAVA_HOME=\"$JDK_21_PATH\"" >> "$BASHRC"
    else
        echo "export JAVA_HOME=\"/usr/lib/jvm/java-21-openjdk-amd64\"" >> "$BASHRC"
    fi
    echo "export PATH=\"\$JAVA_HOME/bin:\$PATH\"" >> "$BASHRC"
    echo "Java 21 environment variables added to $BASHRC."
else
    # Validate that existing JAVA_HOME points to JDK 21
    if [ -n "$JAVA_HOME" ]; then
        CURRENT_JAVA_VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1 | grep -oP '(?<=version ").*?(?=")')
        CURRENT_JAVA_MAJOR=$(echo "$CURRENT_JAVA_VERSION" | cut -d '.' -f 1)
        if [ "$CURRENT_JAVA_MAJOR" != "21" ]; then
            echo "ERROR: JAVA_HOME is set to JDK $CURRENT_JAVA_MAJOR, but this project requires JDK 21."
            echo "       Please update JAVA_HOME in $BASHRC to point to: ${JDK_21_PATH:-/usr/lib/jvm/java-21-openjdk-amd64}"
            exit 1
        fi
    fi
    echo "Java environment variables already present in $BASHRC (verified JDK 21)."
fi

# Configure ANDROID_HOME
if ! grep -q "ANDROID_HOME" "$BASHRC"; then
    echo "" >> "$BASHRC"
    echo "# Android SDK environment variables" >> "$BASHRC"
    echo "export ANDROID_HOME=\$HOME/Android/Sdk" >> "$BASHRC"
    echo "export PATH=\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH" >> "$BASHRC"
    echo "Android SDK environment variables added to $BASHRC."
else
    echo "Android SDK environment variables already present in $BASHRC."
fi

# 5. Bootstrap Gradle Wrapper
echo "[5/5] Bootstrapping Gradle Wrapper..."
GRADLE_VERSION="8.10.2"
GRADLE_SHA256="5d0c8dbf0fd70f36fd9a33a6fe12a2c3a0a57d9e8b8b39cfd92f2c3a2c6c2f5f"
if [ ! -f "gradlew" ]; then
    echo "Gradle wrapper not found in project root. Downloading temporary Gradle distribution to bootstrap..."
    TEMP_DIR=$(mktemp -d)
    wget -q -O "$TEMP_DIR/gradle.zip" "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

    echo "Verifying Gradle distribution checksum..."
    ACTUAL_SHA256=$(sha256sum "$TEMP_DIR/gradle.zip" | cut -d ' ' -f 1)
    if [ "$ACTUAL_SHA256" != "$GRADLE_SHA256" ]; then
        echo "ERROR: Gradle distribution checksum verification failed!"
        echo "       Expected: $GRADLE_SHA256"
        echo "       Got:      $ACTUAL_SHA256"
        rm -rf "$TEMP_DIR"
        exit 1
    fi
    echo "Checksum verified successfully."

    unzip -q "$TEMP_DIR/gradle.zip" -d "$TEMP_DIR"

    echo "Generating Gradle Wrapper files (gradlew, gradlew.bat, gradle/wrapper/)..."
    "$TEMP_DIR/gradle-${GRADLE_VERSION}/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION"

    echo "Cleaning up temporary distribution files..."
    rm -rf "$TEMP_DIR"
    echo "Gradle wrapper successfully generated!"
else
    echo "Gradle wrapper (gradlew) is already present."
fi

echo "================================================"
echo "Installation complete!"
echo "Please restart your terminal or run: source ~/.bashrc"
echo "You can then build the app by running: ./gradlew assembleDebug"
if [ -n "$JDK_21_PATH" ]; then
    echo ""
    echo "If you run into 'Unsupported class file major version' errors, run:"
    echo "  export JAVA_HOME=$JDK_21_PATH"
    echo "before running ./gradlew"
fi
echo "================================================"
