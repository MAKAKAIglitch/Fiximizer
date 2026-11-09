# Building Fiximizer

## Windows one-click build

Run `build.bat` from the repository root.

The automatic Windows builder is adapted from the working builder in [MAKAKAIglitch/Rusify](https://github.com/MAKAKAIglitch/Rusify):

- Uses a complete JDK 21 or JDK 25 to run Gradle, preferring the selected `JAVA_HOME` / `PATH` runtime.
- Verifies that `java.exe` and `javac.exe` exist and report the same supported major version.
- If neither JDK 21 nor JDK 25 is available, downloads Eclipse Temurin 21 through the official Adoptium API.
- Verifies the fallback archive against the SHA-256 checksum returned by Adoptium before extraction.
- Stores the isolated fallback JDK in `.tools/jdk-21-temurin`.
- Gradle resolves the Java 21 compilation toolchain separately; the built mod remains Java 21 bytecode even when Gradle itself runs on JDK 25.
- Does not modify system Java settings or replace another installed Java version.
- Uses the repository's pinned Gradle Wrapper and reuses Gradle caches.
- Builds the mod into `build/libs/`.

Examples:

```bat
build.bat
build.bat clean build
build.bat runClient
```

The first run may download the JDK 21 fallback (only when neither JDK 21 nor JDK 25 is installed), Gradle and Minecraft/NeoForge dependencies. Later runs reuse cached files.

## Minecraft / NeoForge compatibility

- Minecraft: 1.21.1
- Minimum supported NeoForge: 21.1.256
- Accepted NeoForge range: `[21.1.256, 21.2)`
- CI checks the minimum (21.1.256) and a newer 21.1.x build (21.1.265).

Minecraft 1.21.1 / NeoForge's documented baseline is Java 21. The JDK 25 CI job verifies that Gradle, tests, and the Java 21 toolchain work when Gradle itself runs on JDK 25; it is not a full in-game launch certification for running this older Minecraft version on Java 25.

The builder defaults to the minimum supported NeoForge version so the produced JAR does not accidentally depend on APIs introduced only in a later 21.1.x release.

## Linux one-click build

Run the project-local builder from the repository root:

```sh
./build.sh
```

Examples:

```sh
./build.sh clean build
./build.sh runClient
./build.sh build --no-daemon
```

The Linux builder follows the same approach as the Windows builder:

- Searches `JAVA_HOME`, `javac` in `PATH`, common Linux JDK locations, and common Prism Launcher / MultiMC runtime directories.
- Accepts a complete JDK 21 or 25 with matching `java` and `javac` versions for the Gradle runtime.
- If neither is found, downloads Eclipse Temurin 21 for the detected Linux CPU architecture through the official Adoptium API.
- Verifies the downloaded archive against Adoptium's published SHA-256 checksum before extraction.
- Installs the fallback JDK under `.tools/jdk-21-temurin`; it does not modify system Java packages, symlinks, or shell startup files.
- Uses Gradle's separately resolved Java 21 toolchain to compile the mod with `--release 21`, even when Gradle runs on JDK 25.
- Uses the repository's Gradle Wrapper and reuses the normal Gradle caches.

The automatic download requires `curl`, `tar`, and a SHA-256 utility (`sha256sum` or `shasum`). If JDK 21 or JDK 25 is already installed, the builder does not need to download a JDK.

## Manual build

With an installed JDK 21 or 25 as the Gradle runtime:

```bat
gradlew.bat build
```

Linux/macOS:

```bash
./gradlew build
```

The Gradle daemon may run on JDK 21 or 25. The project compiles with the Java 21 toolchain and `--release 21`, so the output JAR remains loadable by Java 21.

## OpenCL runtime

Fiximizer checks for OpenCL GPU devices through LWJGL. The OpenCL ICD/runtime is supplied by the installed GPU driver. The build script does not install a GPU driver or a separate OpenCL runtime.

## Output

The mod JAR is written to `build/libs/`. Its filename is generated from `mod_id` and `mod_version` in `gradle.properties` (for the current project version: `fiximizer-0.14.0-alpha.jar`).

## Local tools and caches

- Isolated JDK: `.tools/jdk-21-temurin`
- JDK download workspace: `.tools/downloads`
- Gradle wrapper, dependencies and build cache: standard Gradle user cache
- Compiled outputs: `build/`

All generated tools and build outputs are excluded from Git.
