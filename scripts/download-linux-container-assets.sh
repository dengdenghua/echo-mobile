#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Linux 容器 assets 下载脚本 —— 为 LinuxSandbox 工具准备 PRoot 二进制 + rootfs
#
# 用途:
#   Octopus Mobile 的 run_shell/run_shell_session 工具通过 PRoot 在 Android 上
#   跑 Linux 容器(无 root,ptrace syscall 翻译)。assets 需要预置:
#     app/src/main/assets/proot/proot-aarch64                      # PRoot 二进制(共享)
#     app/src/main/assets/proot/alpine-minirootfs.tar.gz           # Alpine minirootfs(打包进 APK)
#     app/src/main/assets/proot/alpine-minirootfs.tar.gz.sha256    # SHA256 校验
#
#   Ubuntu rootfs 不打包进 APK(28MB 太大),由 App 运行时从 cdimage.ubuntu.com 下载到
#   filesDir/linux-container-ubuntu-downloads/。本脚本可选地把 Ubuntu rootfs 也下载到
#   assets(供离线场景或开发测试用),但默认不下载,通过 --ubuntu 开启。
#
# 这两个文件不打包进 git 仓库(大文件 + 二进制),由本脚本在构建前下载。
# LinuxSandbox.kt 的 extractProot/extractRootfs 会从 assets 读取并校验 SHA256。
#
# PRoot 二进制来源说明:
#   - proot-me/proot 官方 release(v5.4.0)只提供源码,无预编译二进制
#   - 本脚本从 Termux APT 仓库下载 proot_*.deb 并解压提取二进制
#   - Termux proot 是动态链接(interpreter=/system/bin/linker64),依赖 Android bionic
#   - 注意:Termux proot 可能依赖 /data/data/com.termux/files/usr/lib 下的 talloc
#     在非 Termux 环境跑可能需要额外配置 LD_LIBRARY_PATH 或自行静态编译
#   - 推荐生产环境用 NDK 交叉编译 PRoot 静态二进制(参考 build-native.sh 模式)
#
# Alpine rootfs 来源:
#   - dl-cdn.alpinelinux.org 官方 latest-stable(aarch64 minirootfs)
#   - 约 3MB,含 /bin/sh + busybox,够 PRoot 跑基础命令
#
# Ubuntu rootfs 来源(--ubuntu 开启):
#   - cdimage.ubuntu.com 官方 Ubuntu Base 24.04.4 arm64
#   - 约 28MB,glibc + apt 完整生态
#   - 默认不下载,运行时由用户在设置页触发下载
#
# 用法:
#   ./scripts/download-linux-container-assets.sh                # 默认只下载 Alpine(打包进 APK)
#   ./scripts/download-linux-container-assets.sh --ubuntu       # 同时下载 Ubuntu rootfs 到 assets(可选)
#   ./scripts/download-linux-container-assets.sh --alpine-only  # 显式只下载 Alpine
#   ./scripts/download-linux-container-assets.sh --help
#
# 输出文件大小参考:
#   - proot-aarch64           ~230 KB(Termux 动态链接版)
#   - alpine-minirootfs.tar.gz ~3 MB(打包进 APK)
#   - ubuntu-base-*.tar.gz    ~28 MB(默认不下载,运行时按需下载)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ─── 参数解析 ────────────────────────────────────────────────────────────────
DOWNLOAD_UBUNTU=false
DOWNLOAD_ALPINE=true

while [[ $# -gt 0 ]]; do
    case "$1" in
        --ubuntu)
            DOWNLOAD_UBUNTU=true
            shift
            ;;
        --alpine-only)
            DOWNLOAD_ALPINE=true
            DOWNLOAD_UBUNTU=false
            shift
            ;;
        --no-alpine)
            DOWNLOAD_ALPINE=false
            shift
            ;;
        --help|-h)
            cat <<EOF
用法: $(basename "$0") [选项]

选项:
  --ubuntu          同时下载 Ubuntu 24.04 rootfs 到 assets(默认不下载)
  --alpine-only     只下载 Alpine(默认行为)
  --no-alpine       跳过 Alpine rootfs 下载(仅当与 --ubuntu 组合时有用)
  --help, -h        显示帮助

默认行为:下载 PRoot 二进制 + Alpine minirootfs(打包进 APK)。
Ubuntu rootfs 默认不下载,App 运行时由用户在设置页按需下载到 filesDir。
EOF
            exit 0
            ;;
        *)
            echo "错误:未知参数 $1(用 --help 查看用法)" >&2
            exit 1
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets/proot"

mkdir -p "$ASSETS_DIR"

# 计算总步数(用于 [x/N] 进度显示)
TOTAL_STEPS=1  # PRoot 必下载
[[ "$DOWNLOAD_ALPINE" == "true" ]] && TOTAL_STEPS=$((TOTAL_STEPS + 1))
[[ "$DOWNLOAD_UBUNTU" == "true" ]] && TOTAL_STEPS=$((TOTAL_STEPS + 1))
CURRENT_STEP=0

echo "==> Linux 容器 assets 下载脚本"
echo "    目标目录: $ASSETS_DIR"
echo "    Alpine: $DOWNLOAD_ALPINE  Ubuntu: $DOWNLOAD_UBUNTU"
echo

# ─── 工具检测 ────────────────────────────────────────────────────────────────
if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
    echo "错误: 需要 curl 或 wget,请先安装。" >&2
    exit 1
fi
fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fSL "$1" -o "$2"
    else
        wget -q "$1" -O "$2"
    fi
}
fetch_to_stdout() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$1"
    else
        wget -q -O - "$1"
    fi
}

if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
    echo "错误: 需要 sha256sum 或 shasum,请先安装。" >&2
    exit 1
fi
compute_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# bsdtar(macOS 自带 tar)能解压 .deb(ar 归档)和 .tar.xz
if ! tar --version 2>&1 | grep -qi 'bsd\|libarchive'; then
    echo "警告: 当前 tar 不是 bsdtar/libarchive,可能无法解压 .deb。" >&2
    echo "    macOS 自带 tar 是 bsdtar,Linux 需要 bsdtar 或 7z 替代。" >&2
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# ─── 1. 下载 PRoot 二进制(从 Termux APT .deb 包提取) ──────────────────────
PROOT_BIN="$ASSETS_DIR/proot-aarch64"
CURRENT_STEP=$((CURRENT_STEP + 1))

echo "==> [$CURRENT_STEP/$TOTAL_STEPS] 下载 PRoot 二进制(aarch64,来自 Termux APT)"
# Termux APT 仓库的 proot 包目录:https://packages.termux.dev/apt/termux-main/pool/main/p/proot/
# 目录下有多个版本的 .deb,选最新的 aarch64 版本。
PROOT_INDEX_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/"
PROOT_DEB_URL=$(fetch_to_stdout "$PROOT_INDEX_URL" \
    | grep -oE 'proot_[^"]*_aarch64\.deb' | sort -V | tail -1 || true)

if [[ -z "$PROOT_DEB_URL" ]]; then
    echo "错误: 无法从 Termux APT 解析 proot aarch64 .deb 文件名。" >&2
    echo "    请手动访问 $PROOT_INDEX_URL" >&2
    exit 1
fi
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/$PROOT_DEB_URL"
echo "    来源: $PROOT_DEB_URL"

PROOT_DEB="$TMP_DIR/proot.deb"
fetch "$PROOT_DEB_URL" "$PROOT_DEB"

# 解压 .deb(bsdtar 支持 ar 归档格式)
tar -xf "$PROOT_DEB" -C "$TMP_DIR" 2>/dev/null || {
    echo "错误: 解压 .deb 失败。请确认 tar 是 bsdtar(libarchive)。" >&2
    exit 1
}

# 解压 data.tar.xz 拿到 proot 二进制
if [[ -f "$TMP_DIR/data.tar.xz" ]]; then
    tar -xJf "$TMP_DIR/data.tar.xz" -C "$TMP_DIR" 2>/dev/null || true
elif [[ -f "$TMP_DIR/data.tar.gz" ]]; then
    tar -xzf "$TMP_DIR/data.tar.gz" -C "$TMP_DIR" 2>/dev/null || true
fi

PROOT_SRC=$(find "$TMP_DIR" -path '*/bin/proot' -type f | head -1)
if [[ -z "$PROOT_SRC" ]]; then
    echo "错误: .deb 中未找到 proot 二进制。" >&2
    exit 1
fi

cp "$PROOT_SRC" "$PROOT_BIN"
chmod 755 "$PROOT_BIN"
echo "    完成: $(ls -lh "$PROOT_BIN" | awk '{print $5}') $(basename "$PROOT_BIN")"
echo "    (Termux 动态链接版,interpreter=/system/bin/linker64)"
echo

# ─── 2. 下载 Alpine minirootfs(aarch64) ────────────────────────────────────
if [[ "$DOWNLOAD_ALPINE" == "true" ]]; then
    CURRENT_STEP=$((CURRENT_STEP + 1))
    ROOTFS_TAR="$ASSETS_DIR/alpine-minirootfs.tar.gz"
    ROOTFS_SHA="$ASSETS_DIR/alpine-minirootfs.tar.gz.sha256"

    echo "==> [$CURRENT_STEP/$TOTAL_STEPS] 下载 Alpine minirootfs(aarch64)"
    # 用 latest-stable 路径,自动跟随上游版本。从 latest-releases.yaml 解析文件名。
    ROOTFS_INDEX_URL="https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml"
    ROOTFS_URL=$(fetch_to_stdout "$ROOTFS_INDEX_URL" \
        | grep -oE 'alpine-minirootfs-[0-9.]*-aarch64\.tar\.gz' | head -1 || true)

    if [[ -z "$ROOTFS_URL" ]]; then
        # fallback:硬编码 v3.20 路径
        ROOTFS_URL="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
        echo "    (警告:无法解析 latest-stable,fallback 到 $ROOTFS_URL)"
    else
        ROOTFS_URL="https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/$ROOTFS_URL"
    fi

    echo "    来源: $ROOTFS_URL"
    fetch "$ROOTFS_URL" "$ROOTFS_TAR"

    SHA=$(compute_sha256 "$ROOTFS_TAR")
    echo "$SHA" > "$ROOTFS_SHA"
    echo "    完成: $(ls -lh "$ROOTFS_TAR" | awk '{print $5}') alpine-minirootfs.tar.gz"
    echo "    SHA256: $SHA"
    echo
fi

# ─── 3. (可选)下载 Ubuntu Base rootfs(aarch64) ─────────────────────────────
if [[ "$DOWNLOAD_UBUNTU" == "true" ]]; then
    CURRENT_STEP=$((CURRENT_STEP + 1))
    # 固化版本 24.04.4。LinuxSandbox.kt 的 Distro.UBUNTU 也固化同一版本,
    # 升级时同时改这里的 URL 和 LinuxSandbox.kt 的 downloadUrl/rootfsFileName。
    UBUNTU_VERSION="24.04.4"
    UBUNTU_TAR="$ASSETS_DIR/ubuntu-base-${UBUNTU_VERSION}-base-arm64.tar.gz"
    UBUNTU_SHA="$ASSETS_DIR/ubuntu-base-${UBUNTU_VERSION}-base-arm64.tar.gz.sha256"
    UBUNTU_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-${UBUNTU_VERSION}-base-arm64.tar.gz"
    UBUNTU_SHA_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS"
    UBUNTU_FILENAME="ubuntu-base-${UBUNTU_VERSION}-base-arm64.tar.gz"

    echo "==> [$CURRENT_STEP/$TOTAL_STEPS] 下载 Ubuntu Base ${UBUNTU_VERSION} rootfs(arm64)"
    echo "    来源: $UBUNTU_URL"
    echo "    (注:Ubuntu rootfs 默认不打包进 APK,App 运行时按需下载到 filesDir)"
    echo "    (本步骤仅供开发测试/离线场景预下载)"

    fetch "$UBUNTU_URL" "$UBUNTU_TAR"

    # 从官方 SHA256SUMS 提取对应文件的 SHA
    UBUNTU_SUMS=$(fetch_to_stdout "$UBUNTU_SHA_URL")
    EXPECTED_SHA=$(echo "$UBUNTU_SUMS" | grep -E "\\s+\\*?${UBUNTU_FILENAME}\$" | awk '{print $1}' || true)
    if [[ -z "$EXPECTED_SHA" ]]; then
        echo "警告: SHA256SUMS 中未找到 $UBUNTU_FILENAME,跳过校验" >&2
        EXPECTED_SHA=$(compute_sha256 "$UBUNTU_TAR")
    fi

    ACTUAL_SHA=$(compute_sha256 "$UBUNTU_TAR")
    if [[ "${EXPECTED_SHA,,}" != "${ACTUAL_SHA,,}" ]]; then
        echo "错误: Ubuntu rootfs SHA256 校验失败" >&2
        echo "    expected: $EXPECTED_SHA" >&2
        echo "    actual:   $ACTUAL_SHA" >&2
        rm -f "$UBUNTU_TAR"
        exit 1
    fi
    echo "$EXPECTED_SHA" > "$UBUNTU_SHA"
    echo "    完成: $(ls -lh "$UBUNTU_TAR" | awk '{print $5}') $(basename "$UBUNTU_TAR")"
    echo "    SHA256: $EXPECTED_SHA"
    echo
fi

# ─── 校验 ────────────────────────────────────────────────────────────────────
echo "==> 校验"
echo "    PRoot 二进制: $(file "$PROOT_BIN" 2>/dev/null | cut -d: -f2- || echo 'unknown')"
echo "    PRoot SHA256: $(compute_sha256 "$PROOT_BIN")"
if [[ "$DOWNLOAD_ALPINE" == "true" ]]; then
    echo "    Alpine rootfs SHA256 已写入: alpine-minirootfs.tar.gz.sha256"
fi
if [[ "$DOWNLOAD_UBUNTU" == "true" ]]; then
    echo "    Ubuntu rootfs SHA256 已写入: ubuntu-base-*.tar.gz.sha256"
fi
echo
echo "==> 完成。assets 已就绪,可执行 ./gradlew :app:assembleDebug 打包。"
echo
echo "    注意:"
echo "    - 这些文件不进 git 仓库(已在 .gitignore 排除)。"
echo "    - 默认 APK 体积增加 ~3.3MB(仅 Alpine)。"
echo "    - Ubuntu rootfs 不打包进 APK(运行时按需下载到 filesDir)。"
echo "    - 升级 PRoot/Alpine/Ubuntu:重跑本脚本即可,会覆盖旧文件。"
echo "    - Termux proot 是动态链接版,若运行时崩溃,需用 NDK 自行静态编译 PRoot。"
