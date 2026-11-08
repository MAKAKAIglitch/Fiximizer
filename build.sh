#!/bin/sh
# Fiximizer automatic Linux builder.
# Uses an existing JDK 21 or installs an isolated Eclipse Temurin JDK 21 in .tools/.
set -eu

PROJECT_ROOT=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
TOOLS_ROOT="$PROJECT_ROOT/.tools"
JDK_INSTALL_ROOT="$TOOLS_ROOT/jdk-21-temurin"
DOWNLOAD_ROOT="$TOOLS_ROOT/downloads"
TEMP_EXTRACT_ROOT=""
TEMP_ARCHIVE=""
FOUND_JDK_HOME=""
FOUND_JDK_VERSION=""

info() { printf '\033[1;36m[INFO]\033[0m %s\n' "$*"; }
ok() { printf '\033[1;32m[ OK ]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[WARN]\033[0m %s\n' "$*"; }
error() { printf '\033[1;31m[ERROR]\033[0m %s\n' "$*" >&2; }

cleanup() {
    if [ -n "$TEMP_EXTRACT_ROOT" ] && [ -d "$TEMP_EXTRACT_ROOT" ]; then
        rm -rf "$TEMP_EXTRACT_ROOT"
    fi
    if [ -n "$TEMP_ARCHIVE" ] && [ -f "$TEMP_ARCHIVE" ]; then
        rm -f "$TEMP_ARCHIVE"
    fi
}
trap cleanup 0

fail() {
    error "$*"
    error "No system-wide Java installation or shell environment settings were changed."
    exit 1
}

is_jdk21() {
    CHECK_HOME=$1
    [ -x "$CHECK_HOME/bin/java" ] || return 1
    [ -x "$CHECK_HOME/bin/javac" ] || return 1

    CHECK_JAVA_VERSION=$("$CHECK_HOME/bin/java" -version 2>&1) || return 1
    CHECK_JAVAC_VERSION=$("$CHECK_HOME/bin/javac" -version 2>&1) || return 1

    printf '%s\n' "$CHECK_JAVA_VERSION" | grep -Eq 'version "21([.]|")|openjdk 21([.[:space:]]|$)' || return 1
    printf '%s\n' "$CHECK_JAVAC_VERSION" | grep -Eq '^javac 21([.[:space:]]|$)' || return 1
    return 0
}

try_jdk_candidate() {
    TRY_CANDIDATE=$1
    [ -n "$TRY_CANDIDATE" ] && [ -d "$TRY_CANDIDATE" ] || return 1

    if is_jdk21 "$TRY_CANDIDATE"; then
        FOUND_JDK_HOME=$(CDPATH= cd -P "$TRY_CANDIDATE" && pwd)
        FOUND_JDK_VERSION=$("$FOUND_JDK_HOME/bin/java" -version 2>&1 | sed -n '1p')
        return 0
    fi
    return 1
}

find_jdk21() {
    FOUND_JDK_HOME=""
    FOUND_JDK_VERSION=""

    if [ -n "${JAVA_HOME:-}" ]; then
        if try_jdk_candidate "$JAVA_HOME"; then
            return 0
        fi
        warn "JAVA_HOME does not point to a complete JDK 21; checking other known locations."
    fi

    # Resolve javac from PATH (including common /usr/bin alternatives symlinks).
    if command -v javac >/dev/null 2>&1 && command -v readlink >/dev/null 2>&1; then
        PATH_JAVAC=$(command -v javac)
        REAL_JAVAC=$(readlink -f "$PATH_JAVAC" 2>/dev/null || true)
        if [ -n "$REAL_JAVAC" ]; then
            PATH_JDK_HOME=$(dirname "$(dirname "$REAL_JAVAC")")
            if try_jdk_candidate "$PATH_JDK_HOME"; then
                return 0
            fi
        fi
    fi

    # Common Linux JDK locations and Prism Launcher runtime directories.
    for CANDIDATE in \
        "$JDK_INSTALL_ROOT" \
        /usr/lib/jvm/* \
        /usr/java/* \
        /opt/jdk* \
        /opt/java/* \
        "${HOME:-/nonexistent}"/.local/share/PrismLauncher/java \
        "${HOME:-/nonexistent}"/.local/share/PrismLauncher/java/* \
        "${HOME:-/nonexistent}"/.local/share/PrismLauncher/runtime/* \
        "${HOME:-/nonexistent}"/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/* \
        "${HOME:-/nonexistent}"/.local/share/PolyMC/java/* \
        "${HOME:-/nonexistent}"/.local/share/MultiMC/java/*
    do
        if try_jdk_candidate "$CANDIDATE"; then
            return 0
        fi
    done

    return 1
}

install_temurin21() {
    command -v curl >/dev/null 2>&1 || fail "curl is required to download Temurin automatically. Install curl or provide JDK 21 through JAVA_HOME."
    command -v tar >/dev/null 2>&1 || fail "tar is required to extract Temurin. Install tar or provide JDK 21 through JAVA_HOME."

    case "$(uname -m)" in
        x86_64|amd64) ARCHITECTURE="x64" ;;
        aarch64|arm64) ARCHITECTURE="aarch64" ;;
        ppc64le) ARCHITECTURE="ppc64le" ;;
        s390x) ARCHITECTURE="s390x" ;;
        riscv64) ARCHITECTURE="riscv64" ;;
        armv7l|armv6l) ARCHITECTURE="arm" ;;
        *) fail "Automatic Temurin installation does not yet support CPU architecture '$(uname -m)'. Install a full JDK 21 and set JAVA_HOME." ;;
    esac

    if command -v sha256sum >/dev/null 2>&1; then
        SHA256_TOOL="sha256sum"
    elif command -v shasum >/dev/null 2>&1; then
        SHA256_TOOL="shasum"
    else
        fail "sha256sum or shasum is required to verify the JDK download."
    fi

    mkdir -p "$TOOLS_ROOT" "$DOWNLOAD_ROOT"
    TEMP_ARCHIVE="$DOWNLOAD_ROOT/temurin-21-$$.tar.gz"
    TEMP_EXTRACT_ROOT="$DOWNLOAD_ROOT/temurin-21-extracted-$$"
    mkdir -p "$TEMP_EXTRACT_ROOT"

    API_URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/$ARCHITECTURE/jdk/hotspot/normal/eclipse"
    info "No complete JDK 21 was found."
    info "Downloading an isolated Eclipse Temurin JDK 21 ($ARCHITECTURE) into .tools/; system Java settings will remain unchanged."
    info "Resolving the official Adoptium download..."

    DOWNLOAD_URL=$(curl --fail --silent --show-error --output /dev/null --write-out '%{redirect_url}' "$API_URL") \
        || fail "Adoptium did not provide a JDK download URL. Check the network connection and try again."
    [ -n "$DOWNLOAD_URL" ] || fail "Adoptium returned an empty download URL."

    info "Downloading Temurin JDK 21..."
    curl --fail --location --silent --show-error --retry 3 --connect-timeout 20 \
        --output "$TEMP_ARCHIVE" "$DOWNLOAD_URL" \
        || fail "The JDK archive download failed."

    info "Fetching the official SHA-256 checksum..."
    EXPECTED_CHECKSUM=$(curl --fail --location --silent --show-error \
        "$DOWNLOAD_URL.sha256.txt" | awk 'NR == 1 { print $1 }') \
        || fail "Could not retrieve the official SHA-256 checksum."
    printf '%s\n' "$EXPECTED_CHECKSUM" | grep -Eq '^[[:xdigit:]]{64}$' \
        || fail "Adoptium returned a malformed SHA-256 checksum."

    if [ "$SHA256_TOOL" = "sha256sum" ]; then
        ACTUAL_CHECKSUM=$(sha256sum "$TEMP_ARCHIVE" | awk '{ print $1 }')
    else
        ACTUAL_CHECKSUM=$(shasum -a 256 "$TEMP_ARCHIVE" | awk '{ print $1 }')
    fi
    [ "$(printf '%s' "$ACTUAL_CHECKSUM" | tr 'A-F' 'a-f')" = "$(printf '%s' "$EXPECTED_CHECKSUM" | tr 'A-F' 'a-f')" ] \
        || fail "JDK archive SHA-256 mismatch. The downloaded archive was rejected."
    ok "JDK archive SHA-256 verified."

    info "Extracting the verified JDK archive..."
    tar -xzf "$TEMP_ARCHIVE" -C "$TEMP_EXTRACT_ROOT" \
        || fail "The verified JDK archive could not be extracted."

    JAVA_FILE=$(find "$TEMP_EXTRACT_ROOT" -type f -path '*/bin/java' -print -quit)
    [ -n "$JAVA_FILE" ] || fail "The Temurin archive does not contain bin/java."
    EXTRACTED_JDK_HOME=$(dirname "$(dirname "$JAVA_FILE")")
    is_jdk21 "$EXTRACTED_JDK_HOME" \
        || fail "The downloaded package is not a complete JDK 21 installation (java/javac version check failed)."

    if [ -e "$JDK_INSTALL_ROOT" ]; then
        BACKUP_PATH="$JDK_INSTALL_ROOT.previous-$(date -u +%Y%m%d%H%M%S)-$$"
        mv "$JDK_INSTALL_ROOT" "$BACKUP_PATH" \
            || fail "Could not move the previous project-local JDK out of the way."
        warn "Previous local JDK preserved at $BACKUP_PATH"
    fi

    mv "$EXTRACTED_JDK_HOME" "$JDK_INSTALL_ROOT" \
        || fail "Could not install JDK 21 into .tools/jdk-21-temurin."

    try_jdk_candidate "$JDK_INSTALL_ROOT" \
        || fail "JDK 21 was installed, but the final validation failed."
    ok "Installed project-local JDK 21: $FOUND_JDK_HOME"
}

printf '\n Fiximizer automatic Linux builder\n Minecraft 1.21.1 | NeoForge 21.1.256+ | Java 21\n\n'

[ -f "$PROJECT_ROOT/gradlew" ] || fail "gradlew is missing. Download or clone the complete Fiximizer repository."
[ -f "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.jar" ] || fail "Gradle Wrapper JAR is missing from gradle/wrapper/."
[ -f "$PROJECT_ROOT/gradle.properties" ] || fail "gradle.properties is missing."

if ! find_jdk21; then
    install_temurin21
fi

export JAVA_HOME="$FOUND_JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
ok "Using JDK 21: $JAVA_HOME"
printf '%s\n' "$FOUND_JDK_VERSION"

# Build the optional Rust statistics backend only when Cargo is already available.
# Java remains the guaranteed fallback for machines without a Rust toolchain.
case "$(uname -m)" in
    x86_64|amd64)
        NATIVE_RESOURCE_DIR="$PROJECT_ROOT/src/main/resources/assets/fiximizer/native/linux-x86_64"
        NATIVE_LIBRARY="$PROJECT_ROOT/native/network/target/release/libfiximizer_network_native.so"
        rm -f "$NATIVE_RESOURCE_DIR/libfiximizer_network_native.so"
        if command -v cargo >/dev/null 2>&1; then
            info "Building optional Rust network statistics backend..."
            if cargo build --manifest-path "$PROJECT_ROOT/native/network/Cargo.toml" --release; then
                if [ -f "$NATIVE_LIBRARY" ]; then
                    mkdir -p "$NATIVE_RESOURCE_DIR"
                    cp "$NATIVE_LIBRARY" "$NATIVE_RESOURCE_DIR/"
                    ok "Rust network backend staged for linux-x86_64."
                else
                    warn "Rust build succeeded but the expected native library was not found; Java fallback will be used."
                fi
            else
                warn "Rust backend build failed; continuing with the Java network backend."
            fi
        else
            warn "Cargo was not found; continuing with the Java network backend."
        fi
        ;;
    *)
        warn "Rust native backend is not packaged for $(uname -m); continuing with the Java backend."
        ;;
esac

if [ "$#" -eq 0 ]; then
    set -- build --build-cache --parallel
else
    HAS_BUILD_CACHE=0
    for ARGUMENT do
        if [ "$ARGUMENT" = "--build-cache" ]; then
            HAS_BUILD_CACHE=1
            break
        fi
    done
    if [ "$HAS_BUILD_CACHE" -eq 0 ]; then
        set -- "$@" --build-cache
    fi
fi

info "Starting the Gradle Wrapper. Gradle and Minecraft/NeoForge dependencies are downloaded only when needed."
printf '\n'
if sh "$PROJECT_ROOT/gradlew" "$@"; then
    :
else
    BUILD_EXIT_CODE=$?
    fail "Gradle returned exit code $BUILD_EXIT_CODE."
fi

MOD_ID=$(sed -n 's/^mod_id=//p' "$PROJECT_ROOT/gradle.properties" | sed -n '1p')
MOD_VERSION=$(sed -n 's/^mod_version=//p' "$PROJECT_ROOT/gradle.properties" | sed -n '1p')
[ -n "$MOD_ID" ] && [ -n "$MOD_VERSION" ] || fail "Could not read mod_id and mod_version from gradle.properties."
JAR_PATH="$PROJECT_ROOT/build/libs/$MOD_ID-$MOD_VERSION.jar"
[ -f "$JAR_PATH" ] || fail "Gradle completed, but the expected mod JAR was not found at build/libs/$MOD_ID-$MOD_VERSION.jar."

printf '\n\033[1;32m ==========================================\033[0m\n'
printf '\033[1;32m BUILD SUCCESSFUL\033[0m\n'
printf '\033[1;32m ==========================================\033[0m\n'
ok "Mod: $JAR_PATH"
if command -v du >/dev/null 2>&1; then
    ok "Size: $(du -h "$JAR_PATH" | awk '{ print $1 }')"
fi
printf '\nCopy this JAR into your Minecraft instance mods/ folder.\n\n'
