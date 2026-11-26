#pragma once

#include <algorithm>
#include <array>
#include <cmath>

namespace audio {

inline constexpr int BandCount = 16;
struct Band {
    double frequency = 1000;
    double gain = 0;
    double q = 1.4;
};

class Equalizer {
public:
    void configure(int rate, bool enabled, double preGain,
                   const std::array<Band, BandCount>& bands) {
        targetGain_ = enabled ? std::pow(10.0, preGain / 20.0) : 1;
        for (int i = 0; i < BandCount; ++i) {
            const auto& band = bands[i];
            Coefficients c;
            if (enabled && band.gain != 0 && band.frequency < rate * 0.49) {
                const double a = std::pow(10.0, band.gain / 40.0);
                const double w = 6.283185307179586 * band.frequency / rate;
                const double alpha = std::sin(w) / (2 * band.q);
                const double scale = 1 / (1 + alpha / a);
                c.b0 = (1 + alpha * a) * scale;
                c.b1 = c.a1 = -2 * std::cos(w) * scale;
                c.b2 = (1 - alpha * a) * scale;
                c.a2 = (1 - alpha / a) * scale;
            }
            target_[i] = c;
        }
        if (!initialized_) {
            current_ = target_;
            gain_ = targetGain_;
            initialized_ = true;
        }
        smoothing_ = 1 - std::exp(-1.0 / (0.015 * rate));
    }

    void reset() {
        state_ = {};
        current_ = target_;
        gain_ = targetGain_;
    }

    void process(float* stereo, int frames) {
        for (int frame = 0; frame < frames; ++frame) {
            gain_ += (targetGain_ - gain_) * smoothing_;
            double left = stereo[frame * 2] * gain_;
            double right = stereo[frame * 2 + 1] * gain_;
            for (int i = 0; i < BandCount; ++i) {
                auto& c = current_[i];
                const auto& t = target_[i];
                if (std::abs(t.b0 - c.b0) + std::abs(t.b1 - c.b1) +
                    std::abs(t.b2 - c.b2) + std::abs(t.a1 - c.a1) +
                    std::abs(t.a2 - c.a2) < 1e-9) {
                    c = t;
                    if (c.b0 == 1 && c.a1 == 0 && c.a2 == 0) {
                        state_[i] = {};
                        continue;
                    }
                } else {
                    c.b0 += (t.b0 - c.b0) * smoothing_;
                    c.b1 += (t.b1 - c.b1) * smoothing_;
                    c.b2 += (t.b2 - c.b2) * smoothing_;
                    c.a1 += (t.a1 - c.a1) * smoothing_;
                    c.a2 += (t.a2 - c.a2) * smoothing_;
                }
                left = filter(c, state_[i][0], left);
                right = filter(c, state_[i][1], right);
            }
            stereo[frame * 2] = limit(left);
            stereo[frame * 2 + 1] = limit(right);
        }
    }

private:
    struct Coefficients {
        double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    };

    struct Delay {
        double z1 = 0, z2 = 0;
    };

    static double filter(const Coefficients& c, Delay& state, double input) {
        const double output = c.b0 * input + state.z1;
        state.z1 = c.b1 * input - c.a1 * output + state.z2;
        state.z2 = c.b2 * input - c.a2 * output;
        if (std::abs(state.z1) < 1e-24) state.z1 = 0;
        if (std::abs(state.z2) < 1e-24) state.z2 = 0;
        return output;
    }

    static float limit(double sample) {
        return std::isfinite(sample) ? static_cast<float>(std::clamp(sample, -1.0, 1.0)) : 0;
    }

    std::array<Coefficients, BandCount> current_{}, target_{};
    std::array<std::array<Delay, 2>, BandCount> state_{};
    double gain_ = 1, targetGain_ = 1, smoothing_ = 1;
    bool initialized_ = false;
};

} // namespace audio
