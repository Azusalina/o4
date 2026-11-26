#pragma once

#include <cstdint>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswresample/swresample.h>
}

namespace audio {

class Decoder {
public:
    ~Decoder();
    void open(int fd, int64_t offset, int64_t length);
    int sourceRate() const { return codec_->sample_rate; }
    int64_t durationMs() const;
    void setOutputRate(int rate);
    void seek(int64_t positionMs);
    int next(std::vector<float>& output);

private:
    static int read(void* opaque, uint8_t* buffer, int size);
    static int64_t move(void* opaque, int64_t offset, int whence);
    int convert(std::vector<float>& output, const AVFrame* frame, int offset = 0);
    int convertFrame(std::vector<float>& output);

    int fd_ = -1;
    int64_t start_ = 0, size_ = 0, cursor_ = 0;
    AVFormatContext* format_ = nullptr;
    AVIOContext* io_ = nullptr;
    AVCodecContext* codec_ = nullptr;
    AVPacket* packet_ = nullptr;
    AVFrame* frame_ = nullptr;
    SwrContext* resampler_ = nullptr;
    AVStream* track_ = nullptr;
    int outputRate_ = 0;
    bool flushing_ = false;
    bool cafAlac_ = false;
    int64_t seekMs_ = -1;
    AVChannelLayout inputLayout_{};
    int inputFormat_ = -1;
    int inputRate_ = 0;
};

} // namespace audio
