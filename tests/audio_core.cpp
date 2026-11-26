#include "decoder.h"
#include "equalizer.h"

#include <array>
#include <cmath>
#include <fcntl.h>
#include <iostream>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

namespace {

void expect(bool value, const char* reason) {
    if (!value) throw std::runtime_error(reason);
}

std::vector<float> tone(int rate, int frequency) {
    std::vector<float> stereo(static_cast<size_t>(rate) * 2);
    for (int i = 0; i < rate; ++i) {
        stereo[i * 2] = static_cast<float>(0.1 * std::sin(2 * std::acos(-1) * frequency * i / rate));
    }
    return stereo;
}

double energy(const std::vector<float>& stereo) {
    double sum = 0;
    for (size_t i = stereo.size() / 2; i < stereo.size(); i += 2) {
        expect(stereo[i + 1] == 0, "EQ leaked between channels");
        sum += stereo[i] * stereo[i];
    }
    return sum;
}

void testEqualizer() {
    constexpr int rate = 48000;
    auto dry = tone(rate, 1000);
    const double original = energy(dry);
    std::array<audio::Band, audio::BandCount> bands{};
    for (auto& band : bands) band = {1000, 0.75, 1.4};
    audio::Equalizer eq;
    eq.configure(rate, true, -6, bands);
    auto wet = dry;
    eq.process(wet.data(), rate);
    expect(std::abs(10 * std::log10(energy(wet) / original) - 6) < 0.05,
           "16-band gain and pre-gain did not combine as expected");
    eq.configure(rate, false, -6, bands);
    wet = dry;
    eq.process(wet.data(), rate);
    expect(std::abs(10 * std::log10(energy(wet) / original)) < 0.01,
           "EQ bypass did not return to unity");

    for (auto& band : bands) band.gain = 0;
    bands[0] = {1000, -12, 2};
    eq.configure(rate, true, 0, bands);
    eq.reset();
    wet = dry;
    eq.process(wet.data(), rate);
    expect(std::abs(10 * std::log10(energy(wet) / original) + 12) < 0.05,
           "Maximum cut produced unexpected gain");
}

void testDecoder(const char* path, int64_t offset) {
    int fd = open(path, O_RDONLY);
    expect(fd >= 0, "Could not open fixture");
    struct stat info{};
    expect(fstat(fd, &info) == 0, "Could not determine fixture length");
    audio::Decoder decoder;
    decoder.open(fd, offset, info.st_size - offset);
    close(fd);
    expect(decoder.sourceRate() == 44100, "Incorrect source rate");
    expect(std::abs(decoder.durationMs() - 2000) < 60, "Incorrect source duration");
    constexpr int OutputRate = 48000;
    decoder.setOutputRate(OutputRate);
    std::vector<float> buffer;
    int total = 0, count;
    double signal = 0;
    while ((count = decoder.next(buffer)) > 0) {
        total += count;
        for (int i = 0; i < count * 2; ++i) signal += std::abs(buffer[i]);
    }
    expect(std::abs(total - 2 * OutputRate) < 1200, "Incorrect resampled frame count");
    expect(signal > 100, "Decoded samples were unexpectedly quiet");
    decoder.seek(1200);
    total = 0;
    while ((count = decoder.next(buffer)) > 0) total += count;
    expect(std::abs(total - OutputRate * 0.8) < 1600, "Seeking after EOF produced the wrong frame count");
    std::cout << path << " passed\n";
}

} // namespace

int main(int argc, char** argv) {
    testEqualizer();
    std::cout << "16-band EQ, pre-gain, cut and bypass passed\n";
    for (int i = 1; i < argc; ++i) testDecoder(argv[i], i + 1 == argc ? 12 : 0);
}
