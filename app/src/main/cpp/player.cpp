#include "decoder.h"
#include "equalizer.h"

#include <aaudio/AAudio.h>
#include <android/api-level.h>
#include <dlfcn.h>
#include <jni.h>

#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>

namespace {

using Clock = std::chrono::steady_clock;
constexpr int Continue = 0, Finished = 1, Disconnected = 2, Waiting = 3;
constexpr int PreferExclusive = 0, RequireExclusive = 1;

struct StreamCloser {
    void operator()(AAudioStream* stream) const { AAudioStream_close(stream); }
};
struct BuilderCloser {
    void operator()(AAudioStreamBuilder* builder) const { AAudioStreamBuilder_delete(builder); }
};
using Stream = std::unique_ptr<AAudioStream, StreamCloser>;

int sampleBytes(aaudio_format_t format) {
    switch (format) {
        case AAUDIO_FORMAT_PCM_I16: return 2;
        case AAUDIO_FORMAT_PCM_I24_PACKED: return 3;
        case AAUDIO_FORMAT_PCM_I32:
        case AAUDIO_FORMAT_PCM_FLOAT: return 4;
        default: return 0;
    }
}

Stream openStream(int rate, bool exclusive) {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return {};
    std::unique_ptr<AAudioStreamBuilder, BuilderCloser> cleanup(builder);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setSharingMode(builder, exclusive
            ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, exclusive
            ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY : AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStream* raw = nullptr;
    if (AAudioStreamBuilder_openStream(builder, &raw) != AAUDIO_OK) return {};
    Stream stream(raw);
    if (!sampleBytes(AAudioStream_getFormat(raw))) return {};
    if (AAudioStream_getChannelCount(raw) != 2 || AAudioStream_getSampleRate(raw) <= 0) return {};
    if (exclusive) {
        if (AAudioStream_getSharingMode(raw) != AAUDIO_SHARING_MODE_EXCLUSIVE) return {};
        // Newer Android versions expose a public query. Legacy AAudio streams report shared.
        using MMapQuery = bool (*)(AAudioStream*);
        auto isMMap = reinterpret_cast<MMapQuery>(dlsym(RTLD_DEFAULT, "AAudioStream_isMMapUsed"));
        if (isMMap && !isMMap(raw)) return {};
    }
    return stream;
}

class Player {
public:
    Player(int fd, int64_t offset, int64_t length, int policy, int64_t start, bool enabled,
           const std::array<float, 49>& values) : startMs_(start) {
        decoder_.open(fd, offset, length);
        if (policy == RequireExclusive && android_get_device_api_level() < 27) {
            throw std::runtime_error("Exclusive output requires Android 8.1 or later.");
        }
        if ((policy == PreferExclusive || policy == RequireExclusive)
                && android_get_device_api_level() >= 27) {
            stream_ = openStream(decoder_.sourceRate(), true);
            if (!stream_) stream_ = openStream(AAUDIO_UNSPECIFIED, true);
            exclusive_ = bool(stream_);
        }
        if (!stream_ && policy != RequireExclusive) {
            stream_ = openStream(AAUDIO_UNSPECIFIED, false);
        }
        if (!stream_) {
            throw std::runtime_error(policy == RequireExclusive
                    ? "Exclusive output is unavailable on this audio route."
                    : "Cannot open an audio output stream.");
        }
        rate_ = AAudioStream_getSampleRate(stream_.get());
        const auto format = AAudioStream_getFormat(stream_.get());
        floatOutput_ = format == AAUDIO_FORMAT_PCM_FLOAT;
        bytesPerSample_ = sampleBytes(format);
        decoder_.setOutputRate(rate_);
        setEq(enabled, values);
        if (start > 0) decoder_.seek(start);
        if (AAudioStream_requestStart(stream_.get()) != AAUDIO_OK) {
            throw std::runtime_error("Cannot start audio output.");
        }
    }

    void setEq(bool enabled, const std::array<float, 49>& values) {
        std::array<audio::Band, audio::BandCount> bands;
        for (int i = 0; i < audio::BandCount; ++i) {
            bands[i] = {values[1 + i * 3], values[2 + i * 3], values[3 + i * 3]};
        }
        eq_.configure(rate_, enabled, values[0], bands);
    }

    int64_t positionMs() const {
        const auto read = std::clamp<int64_t>(AAudioStream_getFramesRead(stream_.get()), 0, written_);
        return startMs_ + read * 1000 / rate_;
    }

    int64_t durationMs() const { return decoder_.durationMs(); }
    int rate() const { return rate_; }
    bool exclusive() const { return exclusive_; }

    int step() {
        if (AAudioStream_getState(stream_.get()) == AAUDIO_STREAM_STATE_DISCONNECTED) {
            return Disconnected;
        }
        if (cursor_ == frames_ && !eof_) {
            frames_ = decoder_.next(floatBuffer_);
            cursor_ = 0;
            if (frames_ > 0) {
                eq_.process(floatBuffer_.data(), frames_);
                if (!floatOutput_) packIntegers();
            } else {
                eof_ = true;
                const auto waitMs = 200 + 1000LL * AAudioStream_getBufferCapacityInFrames(
                        stream_.get()) / rate_;
                drainDeadline_ = Clock::now() + std::chrono::milliseconds(waitMs);
            }
        }
        if (eof_) {
            return AAudioStream_getFramesRead(stream_.get()) >= written_ ||
                   Clock::now() >= drainDeadline_ ? Finished : Waiting;
        }
        const void* data = floatOutput_ ? static_cast<void*>(floatBuffer_.data() + cursor_ * 2)
                                       : static_cast<void*>(integerBuffer_.data() + cursor_ * 2 * bytesPerSample_);
        const auto count = AAudioStream_write(stream_.get(), data, frames_ - cursor_, 20'000'000);
        if (count == AAUDIO_ERROR_DISCONNECTED) return Disconnected;
        if (count == 0 || count == AAUDIO_ERROR_TIMEOUT || count == AAUDIO_ERROR_WOULD_BLOCK) return Waiting;
        if (count < 0) {
            throw std::runtime_error(std::string("Audio output failed · ") +
                                     AAudio_convertResultToText(count));
        }
        cursor_ += count;
        written_ += count;
        return Continue;
    }

private:
    void packIntegers() {
        integerBuffer_.resize(static_cast<size_t>(frames_) * 2 * bytesPerSample_);
        const long long scale = 1LL << (bytesPerSample_ * 8 - 1);
        for (int i = 0; i < frames_ * 2; ++i) {
            const auto sample = static_cast<uint32_t>(std::clamp(
                    std::llround(floatBuffer_[i] * static_cast<double>(scale)), -scale, scale - 1));
            for (int byte = 0; byte < bytesPerSample_; ++byte) {
                integerBuffer_[i * bytesPerSample_ + byte] = static_cast<uint8_t>(sample >> (8 * byte));
            }
        }
    }

    audio::Decoder decoder_;
    Stream stream_;
    audio::Equalizer eq_;
    std::vector<float> floatBuffer_;
    std::vector<uint8_t> integerBuffer_;
    int64_t startMs_, written_ = 0;
    int rate_ = 0, cursor_ = 0, frames_ = 0, bytesPerSample_ = 0;
    bool exclusive_ = false, floatOutput_ = false, eof_ = false;
    Clock::time_point drainDeadline_{};
};

Player& player(jlong handle) { return *reinterpret_cast<Player*>(handle); }

std::array<float, 49> parameters(JNIEnv* env, jfloatArray values) {
    std::array<float, 49> result{};
    env->GetFloatArrayRegion(values, 0, result.size(), result.data());
    return result;
}

void throwIo(JNIEnv* env, const std::exception& error) {
    if (auto type = env->FindClass("java/io/IOException")) {
        env->ThrowNew(type, error.what());
    }
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_io_folderaudio_player_NativePlayer_open(
        JNIEnv* env, jclass, jint fd, jlong offset, jlong length, jint policy, jlong position,
        jboolean enabled, jfloatArray values) {
    try {
        auto settings = parameters(env, values);
        if (env->ExceptionCheck()) return 0;
        return reinterpret_cast<jlong>(new Player(fd, offset, length, policy, position, enabled, settings));
    } catch (const std::exception& error) {
        throwIo(env, error);
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL Java_io_folderaudio_player_NativePlayer_step(
        JNIEnv* env, jclass, jlong handle) {
    try {
        return player(handle).step();
    } catch (const std::exception& error) {
        throwIo(env, error);
        return Finished;
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_folderaudio_player_NativePlayer_setEq(
        JNIEnv* env, jclass, jlong handle, jboolean enabled, jfloatArray values) {
    auto settings = parameters(env, values);
    if (!env->ExceptionCheck()) player(handle).setEq(enabled, settings);
}

extern "C" JNIEXPORT jlongArray JNICALL Java_io_folderaudio_player_NativePlayer_info(
        JNIEnv* env, jclass, jlong handle) {
    auto& p = player(handle);
    const jlong info[] = {p.positionMs(), p.durationMs(), p.rate(), p.exclusive() ? 1L : 0L};
    jlongArray result = env->NewLongArray(4);
    if (result) env->SetLongArrayRegion(result, 0, 4, info);
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_io_folderaudio_player_NativePlayer_close(
        JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Player*>(handle);
}
