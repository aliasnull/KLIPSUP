#include "klipsup_engine.h"
#include <cassert>
#include <vector>

int main() {
    std::vector<uint8_t> pixels = {128, 128, 128, 255, 200, 100, 50, 255};
    klipsup::ColorGradeParams params{
        0.5f, 1.1f, 1.2f, 0.1f, 0.0f,
        0.0f, 0.0f, 0.0f,
        1.0f, 1.0f, 1.0f,
        1.0f, 1.0f, 1.0f,
        0.0f
    };
    klipsup::processColorGradeRgba(pixels.data(), 2, 1, params);
    assert(pixels[0] > 128);
    return 0;
}
