#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
ndk="$(cd "${1:?Supply the Android NDK directory}" && pwd)"
version=8.1.3
work="$root/.native"
archive="$work/ffmpeg-$version.tar.xz"
source="$work/ffmpeg-$version"
mkdir -p "$work/ffmpeg"

if [[ ! -f "$source/configure" ]]; then
    if [[ ! -f "$archive" ]]; then
        curl --fail --location --retry 2 "https://ffmpeg.org/releases/ffmpeg-$version.tar.xz" -o "$archive.part"
        xz -t "$archive.part"
        mv "$archive.part" "$archive"
    fi
    tar -xf "$archive" -C "$work"
fi

case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo "Run the native build on Linux or macOS." >&2; exit 1 ;;
esac
bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
jobs="$(getconf _NPROCESSORS_ONLN)"
(( jobs <= 8 )) || jobs=8

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    case "$abi" in
        arm64-v8a) arch=aarch64; triple=aarch64-linux-android ;;
        armeabi-v7a) arch=arm; triple=armv7a-linux-androideabi ;;
        x86) arch=x86; triple=i686-linux-android ;;
        x86_64) arch=x86_64; triple=x86_64-linux-android ;;
    esac
    install="$work/ffmpeg/$abi"
    build="$work/build-$abi"
    mkdir -p "$build"
    (
        cd "$build"
        cc="$bin/${triple}26-clang"
        "$source/configure" \
            --prefix="$install" --target-os=android --arch="$arch" --enable-cross-compile \
            --cc="$cc" --ld="$cc" --ar="$bin/llvm-ar" --nm="$bin/llvm-nm" \
            --ranlib="$bin/llvm-ranlib" --strip="$bin/llvm-strip" \
            --enable-pic --enable-shared --disable-static --disable-programs \
            --extra-ldflags=-Wl,-z,max-page-size=16384 \
            --disable-doc --disable-debug --disable-network --disable-autodetect \
            --disable-avdevice --disable-avfilter --disable-swscale --disable-x86asm \
            --disable-everything \
            --enable-demuxer=flac,mov,mp3,wav,caf \
            --enable-parser=flac,mpegaudio \
            --enable-decoder=flac,alac,mp3float,pcm_u8,pcm_s8,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_s64le,pcm_f32le,pcm_f32be,pcm_f64le,pcm_f64be,pcm_alaw,pcm_mulaw,adpcm_ima_wav,adpcm_ms
        make -j"$jobs"
        make install
    )
done
