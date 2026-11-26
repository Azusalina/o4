#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
read -r -a flags <<< "$(pkg-config --cflags --libs libavformat libavcodec libswresample libavutil)"
cpp="$root/app/src/main/cpp"
"${CXX:-c++}" -std=c++17 -O2 -Wall -Wextra -Werror -I"$cpp" \
    "$root/tests/audio_core.cpp" "$cpp/decoder.cpp" "${flags[@]}" -o "$temp/audio-test"

ffmpeg -v error -f lavfi -i 'sine=frequency=1000:sample_rate=44100:duration=2' \
    -ac 2 -c:a pcm_s24le "$temp/track.wav"
ffmpeg -v error -i "$temp/track.wav" -c:a flac "$temp/track.flac"
ffmpeg -v error -i "$temp/track.wav" -c:a alac "$temp/track.m4a"
ffmpeg -v error -i "$temp/track.wav" -c:a alac "$temp/track.caf"
ffmpeg -v error -i "$temp/track.wav" -c:a libmp3lame "$temp/track.mp3"
{ printf 'asset-prefix'; cat "$temp/track.wav"; } > "$temp/embedded.audio"
"$temp/audio-test" "$temp"/track.{wav,flac,m4a,caf,mp3} "$temp/embedded.audio"
