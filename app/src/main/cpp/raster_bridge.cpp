#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <lcms2.h>
#include <libheif/heif.h>
#include <libheif/heif_properties.h>
#include <libheif/heif_metadata.h>
#include <tiffio.h>
#include "mapped_file.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace {

constexpr uint64_t maxPixels = 42000000;
constexpr uint32_t maxDimension = 16384;

std::string utf(JNIEnv* env, jstring text) {
    if (!text) return {};
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (!chars) return {};
    std::string value(chars);
    env->ReleaseStringUTFChars(text, chars);
    return value;
}

bool validSize(uint32_t width, uint32_t height) {
    return width > 0 && height > 0 && width <= maxDimension && height <= maxDimension &&
        static_cast<uint64_t>(width) * height <= maxPixels;
}

jintArray describe(JNIEnv* env, std::array<jint, 4> fields) {
    auto result = env->NewIntArray(fields.size());
    if (result) env->SetIntArrayRegion(result, 0, fields.size(), fields.data());
    return result;
}

struct BitmapLock {
    JNIEnv* env;
    jobject bitmap;
    AndroidBitmapInfo info{};
    void* pixels = nullptr;

    BitmapLock(JNIEnv* env, jobject bitmap) : env(env), bitmap(bitmap) {
        if (bitmap && AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
            AndroidBitmap_lockPixels(env, bitmap, &pixels);
        }
    }
    ~BitmapLock() { if (pixels) AndroidBitmap_unlockPixels(env, bitmap); }
    uint8_t* row(uint32_t y) const { return static_cast<uint8_t*>(pixels) + y * info.stride; }
};

jobject newBitmap(JNIEnv* env, jint width, jint height, jobject colorSpace) {
    const auto bitmap = env->FindClass("android/graphics/Bitmap");
    const auto config = env->FindClass("android/graphics/Bitmap$Config");
    auto rgba = env->GetStaticObjectField(config,
        env->GetStaticFieldID(config, "ARGB_8888", "Landroid/graphics/Bitmap$Config;"));
    auto create = env->GetStaticMethodID(bitmap, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;ZLandroid/graphics/ColorSpace;)Landroid/graphics/Bitmap;");
    return env->CallStaticObjectMethod(bitmap, create, width, height, rgba, JNI_TRUE, colorSpace);
}

std::array<int, 2> resized(int width, int height, int edge) {
    if (edge <= 0 || std::max(width, height) <= edge) return {width, height};
    const double scale = edge / static_cast<double>(std::max(width, height));
    return {std::max(1, static_cast<int>(std::lround(width * scale))),
            std::max(1, static_cast<int>(std::lround(height * scale)))};
}

struct HeifSource {
    MappedFile data;
    std::unique_ptr<heif_context, decltype(&heif_context_free)> context{heif_context_alloc(), heif_context_free};
    std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)> handle{nullptr, heif_image_handle_release};
    int primaries = 1;
    int transfer = 13;

    explicit HeifSource(int fd) : data(fd) {
        if (!context || !data.data()) return;
        heif_context_set_maximum_image_size_limit(context.get(), maxDimension);
        heif_context_set_max_decoding_threads(context.get(), 1);
        const auto error = heif_context_read_from_memory_without_copy(context.get(), data.data(), data.size(), nullptr);
        if (error.code != heif_error_Ok) {
            __android_log_print(ANDROID_LOG_WARN, "PhotoMarkCodec", "HEIF container read failed (%d/%d)", error.code, error.subcode);
            return;
        }
        heif_image_handle* image = nullptr;
        if (heif_context_get_primary_image_handle(context.get(), &image).code != heif_error_Ok) return;
        handle.reset(image);
        heif_color_profile_nclx* profile = nullptr;
        if (heif_image_handle_get_nclx_color_profile(image, &profile).code == heif_error_Ok && profile) {
            primaries = profile->color_primaries;
            transfer = profile->transfer_characteristics;
            heif_nclx_color_profile_free(profile);
        }
    }

    int width() const { return heif_image_handle_get_width(handle.get()); }
    int height() const { return heif_image_handle_get_height(handle.get()); }

    bool hasOrientation() const {
        heif_item_id id = 0;
        if (heif_context_get_primary_image_ID(context.get(), &id).code != heif_error_Ok) return false;
        std::array<heif_property_id, 32> properties{};
        const auto count = heif_item_get_transformation_properties(context.get(), id, properties.data(), properties.size());
        for (int i = 0; i < std::min(count, static_cast<int>(properties.size())); ++i) {
            const auto type = heif_item_get_property_type(context.get(), id, properties[i]);
            if (type == heif_item_property_type_transform_mirror || type == heif_item_property_type_transform_rotation) return true;
        }
        return false;
    }
};

float inverseTransfer(float x, int transfer) {
    if (transfer == 18) {
        return x <= .5f ? x * x / 3.f : (std::exp((x - .55991073f) / .17883277f) + .28466892f) / 12.f;
    }
    if (transfer == 16) {
        const float q = std::pow(x, 32.f / 2523.f);
        return std::pow(std::max(q - .8359375f, 0.f) / (18.8515625f - 18.6875f * q), 16384.f / 2610.f);
    }
    if (transfer == 1 || transfer == 14 || transfer == 15) {
        return x < .081f ? x / 4.5f : std::pow((x + .099f) / 1.099f, 1.f / .45f);
    }
    return x <= .04045f ? x / 12.92f : std::pow((x + .055f) / 1.055f, 2.4f);
}

struct SdrConversion {
    std::vector<float> linear;
    std::array<uint8_t, 4097> encoded{};
    std::array<float, 4097> luminanceScale{};
    std::array<float, 9> matrix{1,0,0,0,1,0,0,0,1};

    SdrConversion(int depth, int primaries, int transfer, bool p3) : linear(1 << depth) {
        for (unsigned i = 0; i < linear.size(); ++i) {
            linear[i] = inverseTransfer(i / static_cast<float>(linear.size() - 1), transfer);
        }
        for (int i = 0; i <= 4096; ++i) {
            const float value = i / 4096.f;
            const float srgb = value <= .0031308f ? 12.92f * value : 1.055f * std::pow(value, 1.f / 2.4f) - .055f;
            encoded[i] = static_cast<uint8_t>(std::lround(srgb * 255.f));
            if (transfer == 18) {
                const float ootf = std::pow(value, .2f);
                luminanceScale[i] = ootf * 1.22f / (.22f + value * ootf);
            } else if (transfer == 16) {
                luminanceScale[i] = 1.015f / (.015f + value);
            } else {
                luminanceScale[i] = 1.f;
            }
        }
        // Linear D65 primary conversion. The SDR shoulder above preserves relative hue.
        if (primaries == 9 && !p3) {
            matrix = {1.66049f,-.58764f,-.07285f,-.12455f,1.13290f,-.00835f,-.01815f,-.10058f,1.11873f};
        } else if (primaries == 9 && p3) {
            matrix = {1.34358f,-.28218f,-.06140f,-.06530f,1.07579f,-.01049f,.00282f,-.01960f,1.01678f};
        } else if (primaries == 12 && !p3) {
            matrix = {1.22494f,-.22494f,0,-.04206f,1.04206f,0,-.01964f,-.07864f,1.09828f};
        } else if (primaries != 12 && p3) {
            matrix = {.82259f,.17753f,0,.03320f,.96678f,0,.01709f,.07240f,.91030f};
        }
    }

    template<typename Sample>
    void pixel(const Sample* source, uint8_t* output, bool hasAlpha, bool premultiplied) const {
        const auto maximum = linear.size() - 1;
        const auto a = hasAlpha ? std::min<size_t>(source[3], maximum) : maximum;
        const auto value = [&](unsigned channel) {
            const auto direct = premultiplied ? channel * maximum / std::max<size_t>(a, 1) : channel;
            return linear[std::min(direct, maximum)];
        };
        const float r = value(source[0]);
        const float g = value(source[1]);
        const float b = value(source[2]);
        const float luma = r * .2627f + g * .6780f + b * .0593f;
        const float gain = luminanceScale[index(luma)];
        const auto alpha = a * 255 / maximum;
        for (int i = 0; i < 3; ++i) {
            const float channel = matrix[i * 3] * r + matrix[i * 3 + 1] * g + matrix[i * 3 + 2] * b;
            output[i] = encoded[index(channel * gain)] * alpha / 255;
        }
        output[3] = alpha;
    }

    static size_t index(float x) { return static_cast<size_t>(std::clamp(x, 0.f, 1.f) * 4096.f + .5f); }
};

using Tiff = std::unique_ptr<TIFF, decltype(&TIFFClose)>;
using Profile = std::unique_ptr<void, decltype(&cmsCloseProfile)>;
using Transform = std::unique_ptr<void, decltype(&cmsDeleteTransform)>;

using TiffOptions = std::unique_ptr<TIFFOpenOptions, decltype(&TIFFOpenOptionsFree)>;

TiffOptions tiffOptions() {
    TiffOptions options(TIFFOpenOptionsAlloc(), TIFFOpenOptionsFree);
    if (options) {
        TIFFOpenOptionsSetMaxSingleMemAlloc(options.get(), 128 * 1024 * 1024);
        TIFFOpenOptionsSetMaxCumulatedMemAlloc(options.get(), 192 * 1024 * 1024);
    }
    return options;
}

Tiff openTiff(int fd) {
    const int handle = dup(fd);
    if (handle < 0) return Tiff(nullptr, TIFFClose);
    if (lseek(handle, 0, SEEK_SET) < 0) {
        close(handle);
        return Tiff(nullptr, TIFFClose);
    }
    auto tiff = TIFFFdOpenExt(handle, "PhotoMark", "r", tiffOptions().get());
    if (!tiff) close(handle);
    return Tiff(tiff, TIFFClose);
}

Profile colorProfile(bool p3) {
    if (!p3) return Profile(cmsCreate_sRGBProfile(), cmsCloseProfile);
    cmsCIExyY whitePoint{.3127, .3290, 1};
    cmsCIExyYTRIPLE primaries{{.680, .320, 1}, {.265, .690, 1}, {.150, .060, 1}};
    const cmsFloat64Number parameters[] = {2.4, 1.0 / 1.055, .055 / 1.055, 1.0 / 12.92, .04045};
    std::unique_ptr<cmsToneCurve, decltype(&cmsFreeToneCurve)> curve(
        cmsBuildParametricToneCurve(nullptr, 4, parameters), cmsFreeToneCurve);
    if (!curve) return Profile(nullptr, cmsCloseProfile);
    cmsToneCurve* curves[] = {curve.get(), curve.get(), curve.get()};
    return Profile(cmsCreateRGBProfile(&whitePoint, &primaries, curves), cmsCloseProfile);
}

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_app_kai_photomark_RasterCodec_nativeHeifInfo(JNIEnv* env, jobject, jint fd) {
    try {
        const HeifSource source(fd);
        if (!source.handle || !validSize(source.width(), source.height())) return nullptr;
        return describe(env, {source.width(), source.height(), source.transfer, source.hasOrientation() ? 1 : 0});
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_kai_photomark_RasterCodec_nativeHeifExif(JNIEnv* env, jobject, jint fd) {
    try {
        const HeifSource source(fd);
        if (!source.handle) return nullptr;
        heif_item_id id = 0;
        if (heif_image_handle_get_list_of_metadata_block_IDs(source.handle.get(), "Exif", &id, 1) != 1) {
            return nullptr;
        }
        const auto size = heif_image_handle_get_metadata_size(source.handle.get(), id);
        if (size < 12 || size > 2 * 1024 * 1024) return nullptr;
        std::vector<uint8_t> block(size);
        if (heif_image_handle_get_metadata(source.handle.get(), id, block.data()).code != heif_error_Ok) {
            return nullptr;
        }
        // ISO/IEC 23008-12 stores a 32-bit offset from the end of the offset field.
        const uint64_t offset = 4ULL + (static_cast<uint32_t>(block[0]) << 24) +
            (static_cast<uint32_t>(block[1]) << 16) + (static_cast<uint32_t>(block[2]) << 8) + block[3];
        if (offset + 8 > size) return nullptr;
        const auto* header = block.data() + offset;
        const bool tiff = (header[0] == 'M' && header[1] == 'M' && header[2] == 0 && header[3] == 42) ||
            (header[0] == 'I' && header[1] == 'I' && header[2] == 42 && header[3] == 0);
        if (!tiff) return nullptr;
        const std::array<jbyte, 6> exif{'E', 'x', 'i', 'f', 0, 0};
        const jsize payload = size - offset;
        auto bytes = env->NewByteArray(payload + exif.size());
        if (bytes) {
            env->SetByteArrayRegion(bytes, 0, exif.size(), exif.data());
            env->SetByteArrayRegion(bytes, exif.size(), payload, reinterpret_cast<const jbyte*>(header));
        }
        return bytes;
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_kai_photomark_RasterCodec_nativeHeif(JNIEnv* env, jobject, jint fd, jint edge, jobject space, jboolean p3) {
    try {
        const HeifSource source(fd);
        if (!source.handle || !validSize(source.width(), source.height())) return nullptr;
        // A context with real pixel limits is checked above before allocating the decoder.
        std::unique_ptr<heif_decoding_options, decltype(&heif_decoding_options_free)> options(
            heif_decoding_options_alloc(), heif_decoding_options_free);
        if (!options) return nullptr;
        options->strict_decoding = 1;
        heif_image* decoded = nullptr;
        const bool highDepth = heif_image_handle_get_luma_bits_per_pixel(source.handle.get()) > 8;
        const auto requested = highDepth ? heif_chroma_interleaved_RRGGBBAA_LE : heif_chroma_interleaved_RGBA;
        const auto error = heif_decode_image(source.handle.get(), &decoded, heif_colorspace_RGB, requested, options.get());
        std::unique_ptr<heif_image, decltype(&heif_image_release)> image(decoded, heif_image_release);
        if (error.code != heif_error_Ok || !image) {
            __android_log_print(ANDROID_LOG_WARN, "PhotoMarkCodec", "HEIF pixel decode failed (%d/%d)", error.code, error.subcode);
            return nullptr;
        }
        const int depth = heif_image_get_bits_per_pixel_range(image.get(), heif_channel_interleaved);
        const int width = heif_image_get_width(image.get(), heif_channel_interleaved);
        const int height = heif_image_get_height(image.get(), heif_channel_interleaved);
        if (depth < 8 || depth > 16 || !validSize(width, height)) {
            __android_log_print(ANDROID_LOG_WARN, "PhotoMarkCodec", "HEIF output properties unavailable (%d, %d, %d)", depth, width, height);
            return nullptr;
        }

        int stride = 0;
        const auto* plane = heif_image_get_plane_readonly(image.get(), heif_channel_interleaved, &stride);
        if (!plane) return nullptr;
        const auto size = resized(width, height, edge);
        auto bitmap = newBitmap(env, size[0], size[1], space);
        if (env->ExceptionCheck()) return nullptr;
        BitmapLock lock(env, bitmap);
        if (!lock.pixels) return nullptr;
        const SdrConversion tone(depth, source.primaries, source.transfer, p3);
        const bool alpha = heif_image_handle_has_alpha_channel(source.handle.get());
        const bool premultiplied = alpha && heif_image_is_premultiplied_alpha(image.get());
        for (int y = 0; y < size[1]; ++y) {
            const auto* row = plane + (static_cast<uint64_t>(y) * height / size[1]) * stride;
            auto* result = lock.row(y);
            for (int x = 0; x < size[0]; ++x) {
                const auto offset = (static_cast<uint64_t>(x) * width / size[0]) * 4;
                if (highDepth) tone.pixel(reinterpret_cast<const uint16_t*>(row) + offset, result + x * 4, alpha, premultiplied);
                else tone.pixel(row + offset, result + x * 4, alpha, premultiplied);
            }
        }
        return bitmap;
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_kai_photomark_RasterCodec_nativeTiffInfo(JNIEnv* env, jobject, jint fd) {
    try {
        auto tiff = openTiff(fd);
        if (!tiff) return nullptr;
        uint32_t width = 0, height = 0;
        uint16_t depth = 0, orientation = ORIENTATION_TOPLEFT, type = 0;
        TIFFGetField(tiff.get(), TIFFTAG_IMAGEWIDTH, &width);
        TIFFGetField(tiff.get(), TIFFTAG_IMAGELENGTH, &height);
        TIFFGetFieldDefaulted(tiff.get(), TIFFTAG_BITSPERSAMPLE, &depth);
        TIFFGetFieldDefaulted(tiff.get(), TIFFTAG_ORIENTATION, &orientation);
        TIFFGetFieldDefaulted(tiff.get(), TIFFTAG_SAMPLEFORMAT, &type);
        if (!validSize(width, height) || (depth != 8 && depth != 16) || type != SAMPLEFORMAT_UINT) return nullptr;
        return describe(env, {static_cast<jint>(width), static_cast<jint>(height), depth, orientation});
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_kai_photomark_RasterCodec_nativeTiff(JNIEnv* env, jobject, jint fd, jint edge, jobject space, jboolean p3) {
    try {
        auto tiff = openTiff(fd);
        uint32_t width = 0, height = 0;
        if (!tiff || !TIFFGetField(tiff.get(), TIFFTAG_IMAGEWIDTH, &width) ||
            !TIFFGetField(tiff.get(), TIFFTAG_IMAGELENGTH, &height) || !validSize(width, height)) return nullptr;
        TIFFSetField(tiff.get(), TIFFTAG_ORIENTATION, ORIENTATION_TOPLEFT);
        std::vector<uint32_t> raster(static_cast<size_t>(width) * height);
        if (!TIFFReadRGBAImageOriented(tiff.get(), width, height, raster.data(), ORIENTATION_TOPLEFT, 1)) return nullptr;

        void* icc = nullptr;
        uint32_t iccLength = 0;
        Profile input(nullptr, cmsCloseProfile);
        if (TIFFGetField(tiff.get(), TIFFTAG_ICCPROFILE, &iccLength, &icc) && iccLength <= 4 * 1024 * 1024) {
            input.reset(cmsOpenProfileFromMem(icc, iccLength));
        }
        if (!input || cmsGetColorSpace(input.get()) != cmsSigRgbData) input = colorProfile(false);
        Profile output = colorProfile(p3);
        Transform transform(input && output ? cmsCreateTransform(input.get(), TYPE_RGBA_8, output.get(),
            TYPE_RGBA_8, INTENT_RELATIVE_COLORIMETRIC, cmsFLAGS_COPY_ALPHA) : nullptr, cmsDeleteTransform);
        const auto size = resized(width, height, edge);
        auto bitmap = newBitmap(env, size[0], size[1], space);
        if (env->ExceptionCheck()) return nullptr;
        BitmapLock lock(env, bitmap);
        if (!lock.pixels) return nullptr;
        for (int y = 0; y < size[1]; ++y) {
            auto* row = lock.row(y);
            const auto* source = raster.data() + (static_cast<uint64_t>(y) * height / size[1]) * width;
            for (int x = 0; x < size[0]; ++x) {
                const auto pixel = source[static_cast<uint64_t>(x) * width / size[0]];
                auto* dst = row + x * 4;
                dst[3] = TIFFGetA(pixel);
                const int alpha = std::max(1, static_cast<int>(dst[3]));
                dst[0] = std::min(255, static_cast<int>(TIFFGetR(pixel)) * 255 / alpha);
                dst[1] = std::min(255, static_cast<int>(TIFFGetG(pixel)) * 255 / alpha);
                dst[2] = std::min(255, static_cast<int>(TIFFGetB(pixel)) * 255 / alpha);
            }
            if (transform) cmsDoTransform(transform.get(), row, row, size[0]);
            for (int x = 0; x < size[0]; ++x) for (int c = 0; c < 3; ++c) {
                row[x * 4 + c] = row[x * 4 + c] * row[x * 4 + 3] / 255;
            }
        }
        return bitmap;
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_kai_photomark_RasterCodec_nativeWriteTiff(
    JNIEnv* env, jobject, jstring path, jobject bitmap, jboolean p3, jstring make, jstring model, jstring date) {
    try {
        BitmapLock lock(env, bitmap);
        if (!lock.pixels) return JNI_FALSE;
        Tiff tiff(TIFFOpenExt(utf(env, path).c_str(), "w", tiffOptions().get()), TIFFClose);
        if (!tiff) return JNI_FALSE;
        TIFFSetField(tiff.get(), TIFFTAG_IMAGEWIDTH, lock.info.width);
        TIFFSetField(tiff.get(), TIFFTAG_IMAGELENGTH, lock.info.height);
        TIFFSetField(tiff.get(), TIFFTAG_ORIENTATION, ORIENTATION_TOPLEFT);
        TIFFSetField(tiff.get(), TIFFTAG_SAMPLESPERPIXEL, 4);
        TIFFSetField(tiff.get(), TIFFTAG_BITSPERSAMPLE, 8);
        TIFFSetField(tiff.get(), TIFFTAG_PHOTOMETRIC, PHOTOMETRIC_RGB);
        TIFFSetField(tiff.get(), TIFFTAG_PLANARCONFIG, PLANARCONFIG_CONTIG);
        TIFFSetField(tiff.get(), TIFFTAG_COMPRESSION, COMPRESSION_LZW);
        TIFFSetField(tiff.get(), TIFFTAG_PREDICTOR, PREDICTOR_HORIZONTAL);
        TIFFSetField(tiff.get(), TIFFTAG_ROWSPERSTRIP, TIFFDefaultStripSize(tiff.get(), 0));
        uint16_t alpha = EXTRASAMPLE_ASSOCALPHA;
        TIFFSetField(tiff.get(), TIFFTAG_EXTRASAMPLES, 1, &alpha);
        auto assign = [&](int tag, jstring value) {
            const auto text = utf(env, value);
            if (!text.empty()) TIFFSetField(tiff.get(), tag, text.c_str());
        };
        assign(TIFFTAG_MAKE, make);
        assign(TIFFTAG_MODEL, model);
        assign(TIFFTAG_DATETIME, date);
        Profile profile = colorProfile(p3);
        uint32_t length = 0;
        std::vector<uint8_t> icc;
        if (profile && cmsSaveProfileToMem(profile.get(), nullptr, &length) && length) {
            icc.resize(length);
            if (cmsSaveProfileToMem(profile.get(), icc.data(), &length)) {
                TIFFSetField(tiff.get(), TIFFTAG_ICCPROFILE, length, icc.data());
            }
        }
        // TIFFWriteScanline may modify its input when applying its predictor.
        std::vector<uint8_t> row(static_cast<size_t>(lock.info.width) * 4);
        for (uint32_t y = 0; y < lock.info.height; ++y) {
            std::copy_n(lock.row(y), row.size(), row.data());
            if (TIFFWriteScanline(tiff.get(), row.data(), y) < 0) return JNI_FALSE;
        }
        return TIFFWriteDirectory(tiff.get()) ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}
