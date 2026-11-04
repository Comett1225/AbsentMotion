FROM ubuntu:24.04
ARG TARGETARCH
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        clang g++ cmake ninja-build python3 git pkg-config curl ca-certificates xz-utils file patchelf binutils \
        libfontconfig-dev libfreetype-dev fakeroot dpkg-dev xvfb xauth fontconfig \
        libasound2t64 libx11-6 libxext6 libxi6 libxrender1 libxtst6 libvulkan1 mesa-vulkan-drivers \
    && rm -rf /var/lib/apt/lists/*
RUN case "$TARGETARCH" in \
        arm64) jdk=aarch64; jsum=e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578; \
               ai=aarch64; asum=f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158; \
               rsum=9a72a744d221576c9347ec074243acc5c3c7568896b12714e0e82e9f0bf47900 ;; \
        amd64) jdk=x64; jsum=d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c; \
               ai=x86_64; asum=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0; \
               rsum=617aa77314d57c8b8e4fae9c3f0b35af7b30d5529cb69e98f64f92510ed98a46 ;; \
        *) exit 1 ;; \
    esac \
    && curl -fsSL -o /tmp/jdk.tgz "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_${jdk}_linux_hotspot_21.0.13_9.tar.gz" \
    && echo "$jsum  /tmp/jdk.tgz" | sha256sum -c \
    && mkdir -p /opt/jdk && tar -xzf /tmp/jdk.tgz -C /opt/jdk --strip-components=1 && rm /tmp/jdk.tgz \
    && curl -fsSL -o /tmp/appimagetool "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-${ai}.AppImage" \
    && echo "$asum  /tmp/appimagetool" | sha256sum -c \
    && curl -fsSL -o /opt/appimage-runtime "https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-${ai}" \
    && echo "$rsum  /opt/appimage-runtime" | sha256sum -c \
    && chmod +x /tmp/appimagetool && cd /opt && /tmp/appimagetool --appimage-extract >/dev/null && mv squashfs-root appimagetool && rm /tmp/appimagetool
ENV JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:/opt/appimagetool/usr/bin:$PATH APPIMAGE_RUNTIME=/opt/appimage-runtime
