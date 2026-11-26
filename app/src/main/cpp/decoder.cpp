#include "decoder.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>
#include <unistd.h>

extern "C" {
#include <libavutil/intreadwrite.h>
}

namespace audio {
namespace {

void check(int result, const char* operation) {
    if (result >= 0) return;
    char detail[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(result, detail, sizeof(detail));
    throw std::runtime_error(std::string(operation) + " · " + detail);
}

} // namespace

Decoder::~Decoder() {
    av_frame_free(&frame_);
    av_packet_free(&packet_);
    avcodec_free_context(&codec_);
    avformat_close_input(&format_);
    if (io_) av_freep(&io_->buffer);
    avio_context_free(&io_);
    swr_free(&resampler_);
    av_channel_layout_uninit(&inputLayout_);
    if (fd_ >= 0) close(fd_);
}

int Decoder::read(void* opaque, uint8_t* buffer, int size) {
    auto& d = *static_cast<Decoder*>(opaque);
    const int count = static_cast<int>(std::min<int64_t>(size, d.size_ - d.cursor_));
    if (count <= 0) return AVERROR_EOF;
    ssize_t bytes;
    do {
        bytes = pread(d.fd_, buffer, count, d.start_ + d.cursor_);
    } while (bytes < 0 && errno == EINTR);
    if (bytes < 0) return AVERROR(errno);
    if (bytes == 0) return AVERROR_EOF;
    d.cursor_ += bytes;
    return static_cast<int>(bytes);
}

int64_t Decoder::move(void* opaque, int64_t offset, int whence) {
    auto& d = *static_cast<Decoder*>(opaque);
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) return d.size_;
    int64_t base = 0;
    if (whence == SEEK_CUR) base = d.cursor_;
    else if (whence == SEEK_END) base = d.size_;
    else if (whence != SEEK_SET) return AVERROR(EINVAL);
    if (offset < -base || offset > d.size_ - base) return AVERROR(EINVAL);
    return d.cursor_ = base + offset;
}

void Decoder::open(int fd, int64_t offset, int64_t length) {
    if (offset < 0 || length <= 0 || offset > INT64_MAX - length) {
        throw std::runtime_error("Audio document is empty or has an invalid size.");
    }
    fd_ = dup(fd);
    if (fd_ < 0) check(AVERROR(errno), "Cannot open audio document");
    start_ = offset;
    size_ = length;
    format_ = avformat_alloc_context();
    if (!format_) throw std::bad_alloc();
    constexpr int BufferSize = 32 * 1024;
    auto* buffer = static_cast<uint8_t*>(av_malloc(BufferSize));
    if (!buffer) throw std::bad_alloc();
    io_ = avio_alloc_context(buffer, BufferSize, 0, this, read, nullptr, move);
    if (!io_) {
        av_free(buffer);
        throw std::bad_alloc();
    }
    format_->pb = io_;
    format_->flags |= AVFMT_FLAG_CUSTOM_IO;
    format_->probesize = 1024 * 1024;
    format_->max_analyze_duration = 2 * AV_TIME_BASE;
    AVDictionary* options = nullptr;
    av_dict_set(&options, "protocol_whitelist", "", 0);
    av_dict_set(&options, "format_whitelist", "flac,mov,mp3,wav,caf", 0);
    const int result = avformat_open_input(&format_, nullptr, nullptr, &options);
    av_dict_free(&options);
    check(result, "Cannot read audio format");
    check(avformat_find_stream_info(format_, nullptr), "Cannot read audio information");
    const AVCodec* decoder = nullptr;
    const int index = av_find_best_stream(format_, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
    check(index, "No supported audio stream");
    track_ = format_->streams[index];
    codec_ = avcodec_alloc_context3(decoder);
    packet_ = av_packet_alloc();
    frame_ = av_frame_alloc();
    if (!codec_ || !packet_ || !frame_) throw std::bad_alloc();
    check(avcodec_parameters_to_context(codec_, track_->codecpar), "Invalid audio parameters");
    codec_->pkt_timebase = track_->time_base;
    cafAlac_ = codec_->codec_id == AV_CODEC_ID_ALAC && std::strcmp(format_->iformat->name, "caf") == 0;
    if (cafAlac_) codec_->flags2 |= AV_CODEC_FLAG2_SKIP_MANUAL;
    check(avcodec_open2(codec_, decoder, nullptr), "Cannot start audio decoder");
}

int64_t Decoder::durationMs() const {
    if (track_->duration != AV_NOPTS_VALUE) {
        return std::max<int64_t>(0, av_rescale_q(track_->duration, track_->time_base, {1, 1000}));
    }
    return format_->duration == AV_NOPTS_VALUE ? 0 : std::max<int64_t>(0, format_->duration / 1000);
}

void Decoder::setOutputRate(int rate) {
    outputRate_ = rate;
    swr_free(&resampler_);
}

void Decoder::seek(int64_t positionMs) {
    const int64_t start = track_->start_time == AV_NOPTS_VALUE ? 0 : track_->start_time;
    const int64_t target = start + av_rescale_q(positionMs, {1, 1000}, track_->time_base);
    check(av_seek_frame(format_, track_->index, target, AVSEEK_FLAG_BACKWARD), "Cannot seek");
    avcodec_flush_buffers(codec_);
    swr_free(&resampler_);
    flushing_ = false;
    seekMs_ = positionMs;
}

int Decoder::convert(std::vector<float>& output, const AVFrame* frame, int offset) {
    const int samples = frame ? frame->nb_samples - offset : 0;
    if (frame && samples <= 0) return 0;
    int capacity = swr_get_out_samples(resampler_, samples);
    check(capacity, "Invalid converted sample count");
    if (capacity > outputRate_ * 4) throw std::runtime_error("Audio frame is too large.");
    if (capacity == 0) return 0;
    output.resize(static_cast<size_t>(capacity) * 2);
    auto* pcm = reinterpret_cast<uint8_t*>(output.data());
    std::array<const uint8_t*, 64> input{};
    if (frame) {
        auto format = static_cast<AVSampleFormat>(frame->format);
        const bool planar = av_sample_fmt_is_planar(format);
        const int channels = frame->ch_layout.nb_channels;
        const int planes = planar ? channels : 1;
        const int stride = av_get_bytes_per_sample(format) * (planar ? 1 : channels);
        for (int i = 0; i < planes; ++i) input[i] = frame->extended_data[i] + offset * stride;
    }
    const int count = swr_convert(resampler_, &pcm, capacity, frame ? input.data() : nullptr, samples);
    check(count, "Cannot convert audio samples");
    return count;
}

int Decoder::convertFrame(std::vector<float>& output) {
    if (frame_->ch_layout.nb_channels <= 0 || frame_->ch_layout.nb_channels > 64 ||
        frame_->sample_rate <= 0) {
        throw std::runtime_error("Invalid audio frame layout.");
    }
    if (!resampler_ || frame_->sample_rate != inputRate_ || frame_->format != inputFormat_ ||
        av_channel_layout_compare(&frame_->ch_layout, &inputLayout_) != 0) {
        swr_free(&resampler_);
        av_channel_layout_uninit(&inputLayout_);
        if (frame_->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) {
            av_channel_layout_default(&inputLayout_, frame_->ch_layout.nb_channels);
        } else {
            check(av_channel_layout_copy(&inputLayout_, &frame_->ch_layout), "Invalid audio layout");
        }
        inputRate_ = frame_->sample_rate;
        inputFormat_ = frame_->format;
        const AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
        check(swr_alloc_set_opts2(&resampler_, &stereo, AV_SAMPLE_FMT_FLT, outputRate_,
                                  &inputLayout_, static_cast<AVSampleFormat>(inputFormat_),
                                  inputRate_, 0, nullptr), "Cannot configure audio converter");
        check(swr_init(resampler_), "Cannot initialize audio converter");
    }

    const int64_t start = track_->start_time == AV_NOPTS_VALUE ? 0 : track_->start_time;
    const int64_t timestamp = frame_->best_effort_timestamp;
    int skip = 0;
    if (cafAlac_) {
        const auto* padding = av_frame_get_side_data(frame_, AV_FRAME_DATA_SKIP_SAMPLES);
        if (padding && padding->size >= 8) {
            skip = static_cast<int>(std::min<uint32_t>(frame_->nb_samples, AV_RL32(padding->data)));
        }
        // CAF can describe a fixed packet size even when the ALAC frame is already short.
        if (timestamp != AV_NOPTS_VALUE && track_->duration != AV_NOPTS_VALUE) {
            const int64_t remaining = av_rescale_q(track_->duration - (timestamp - start),
                                                  track_->time_base, {1, inputRate_});
            frame_->nb_samples = static_cast<int>(std::clamp<int64_t>(remaining, 0, frame_->nb_samples));
        } else if (padding && padding->size >= 8) {
            frame_->nb_samples -= std::min<uint32_t>(frame_->nb_samples, AV_RL32(padding->data + 4));
        }
    }
    if (seekMs_ < 0 || timestamp == AV_NOPTS_VALUE) {
        seekMs_ = -1;
        return convert(output, frame_, skip);
    }
    const int64_t frameMs = av_rescale_q(timestamp - start, track_->time_base, {1, 1000});
    const int64_t offset = seekMs_ - frameMs;
    const int64_t inputSkip = offset > 0
            ? av_rescale_rnd(offset, inputRate_, 1000, AV_ROUND_UP) : 0;
    if (inputSkip >= frame_->nb_samples) return 0;
    seekMs_ = -1;
    return convert(output, frame_, std::max(skip, static_cast<int>(inputSkip)));
}

int Decoder::next(std::vector<float>& output) {
    for (;;) {
        const int decoded = avcodec_receive_frame(codec_, frame_);
        if (decoded == AVERROR_EOF) return resampler_ ? convert(output, nullptr) : 0;
        if (decoded >= 0) {
            const int count = convertFrame(output);
            av_frame_unref(frame_);
            if (count > 0) return count;
            continue;
        }
        if (decoded != AVERROR(EAGAIN)) check(decoded, "Cannot decode audio");
        if (flushing_) return 0;
        int result;
        do {
            av_packet_unref(packet_);
            result = av_read_frame(format_, packet_);
        } while (result >= 0 && packet_->stream_index != track_->index);
        if (result == AVERROR_EOF) {
            flushing_ = true;
            check(avcodec_send_packet(codec_, nullptr), "Cannot drain audio decoder");
        } else {
            check(result, "Cannot read audio packet");
            check(avcodec_send_packet(codec_, packet_), "Cannot submit audio packet");
        }
    }
}

} // namespace audio
