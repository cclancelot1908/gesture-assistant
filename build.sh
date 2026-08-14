#!/bin/sh
# 一键构建脚本（本机 /Volumes/ORICO 工具链专用）
# 用法: ./build.sh            # 编译 debug APK
#       ./build.sh test       # 跑单元测试
set -eu

TC=/Volumes/ORICO/claude/android-toolchain
export JAVA_HOME="$TC/jdk-17.0.20+8/Contents/Home"
export ANDROID_HOME="$TC/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# 清掉会干扰 Java 的 socks5 代理环境变量；Gradle 走 gradle.properties 里的 http 代理
unset ALL_PROXY HTTP_PROXY HTTPS_PROXY http_proxy https_proxy all_proxy || true

GRADLE="$TC/gradle-9.6.1/bin/gradle"
cd "$(dirname "$0")"

if [ "${1:-}" = "test" ]; then
    exec "$GRADLE" testDebugUnitTest --no-daemon --console=plain
else
    "$GRADLE" assembleDebug --no-daemon --console=plain
    echo ""
    echo "✅ APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
fi
