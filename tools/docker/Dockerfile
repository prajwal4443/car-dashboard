# The Android toolchain in a container, so the host needs only Docker: JDK 17, Android SDK 34.
FROM eclipse-temurin:17-jdk

ARG CMDLINE_TOOLS=11076708
ENV ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle \
    HOME=/gradle \
    ANDROID_USER_HOME=/gradle/.android \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends unzip curl && rm -rf /var/lib/apt/lists/*
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL -o /tmp/tools.zip \
       "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
    && unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/tools.zip
ENV PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
# Runs as the caller's user (no root-owned files in the source tree): the Gradle home must be writable by anyone.
RUN mkdir -p /gradle && chmod 777 /gradle && chmod -R a+rX "$ANDROID_HOME"
WORKDIR /workspace
