#include <android/bitmap.h>
#include <jni.h>
#include <libraw/libraw.h>
#include "mapped_file.h"

#include <array>
#include <cmath>
#include <ctime>
#include <memory>
#include <string>

namespace {

struct RawImageDeleter {
    void operator()(libraw_processed_image_t* image) const {
        if (image) LibRaw::dcraw_clear_mem(image);
    }
};

bool open(MappedFile& file, LibRaw& raw) {
    raw.imgdata.rawparams.max_raw_memory_mb = 160;
    return file.data() && raw.open_buffer(file.data(), file.size()) == LIBRAW_SUCCESS;
}

jobject asBitmap(JNIEnv* env, const libraw_processed_image_t& image) {
    if (image.type != LIBRAW_IMAGE_BITMAP || image.colors != 3 || image.bits != 8) return nullptr;
    const auto bitmapClass = env->FindClass("android/graphics/Bitmap");
    const auto configClass = env->FindClass("android/graphics/Bitmap$Config");
    auto config = env->GetStaticObjectField(
        configClass, env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;"));
    auto create = env->GetStaticMethodID(
        bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, image.width, image.height, config);
    if (env->ExceptionCheck() || !bitmap) return nullptr;
    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;

    const unsigned char* source = image.data;
    for (unsigned y = 0; y < image.height; ++y) {
        auto* destination = static_cast<unsigned char*>(pixels) + y * info.stride;
        for (unsigned x = 0; x < image.width; ++x) {
            *destination++ = *source++;
            *destination++ = *source++;
            *destination++ = *source++;
            *destination++ = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_kai_photomark_RawDecoder_nativeDescribe(JNIEnv* env, jobject, jint fd) {
    try {
        MappedFile input(fd);
        LibRaw raw;
        if (!open(input, raw)) return nullptr;
        const auto& data = raw.imgdata;
        char date[32] = "";
        if (data.other.timestamp > 0) {
            std::tm time{};
            if (localtime_r(&data.other.timestamp, &time)) {
                std::strftime(date, sizeof(date), "%Y:%m:%d %H:%M:%S", &time);
            }
        }
        const std::array<std::string, 12> fields {
            data.idata.make, data.idata.model, data.lens.Lens,
            std::to_string(data.other.focal_len), std::to_string(data.other.aperture),
            std::to_string(data.other.shutter), std::to_string(data.other.iso_speed), date,
            std::to_string(data.sizes.iwidth), std::to_string(data.sizes.iheight),
            std::to_string(data.makernotes.nikon.NEFCompression), std::to_string(data.sizes.flip)
        };
        jobjectArray result = env->NewObjectArray(fields.size(), env->FindClass("java/lang/String"), nullptr);
        if (!result) return nullptr;
        for (size_t i = 0; i < fields.size(); ++i) {
            // ASCII metadata is safe for JNI NewStringUTF. EXIF handles other encodings.
            std::string field = fields[i];
            for (char& byte : field) {
                if (static_cast<unsigned char>(byte) >= 128) byte = '?';
            }
            jstring value = env->NewStringUTF(field.c_str());
            if (!value) return nullptr;
            env->SetObjectArrayElement(result, i, value);
            env->DeleteLocalRef(value);
        }
        return result;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_kai_photomark_RawDecoder_nativeDecode(JNIEnv* env, jobject, jint fd, jboolean halfSize) {
    try {
        MappedFile input(fd);
        LibRaw raw;
        if (!open(input, raw)) return nullptr;
        const auto& data = raw.imgdata;
        const auto compression = data.makernotes.nikon.NEFCompression;
        if (compression == 13 || compression == 14) return nullptr;

        const unsigned long long pixels = static_cast<unsigned long long>(data.sizes.iwidth) * data.sizes.iheight;
        if (pixels == 0 || pixels > (halfSize ? 85000000ULL : 40000000ULL)) return nullptr;
        raw.imgdata.params.user_flip = 0;
        raw.imgdata.params.use_camera_wb = 1;
        raw.imgdata.params.half_size = halfSize;
        raw.imgdata.params.output_color = 1;
        raw.imgdata.params.output_bps = 8;
        raw.imgdata.params.user_qual = 0;
        if (raw.unpack() != LIBRAW_SUCCESS || raw.dcraw_process() != LIBRAW_SUCCESS) return nullptr;
        int error = LIBRAW_SUCCESS;
        std::unique_ptr<libraw_processed_image_t, RawImageDeleter> result(raw.dcraw_make_mem_image(&error));
        return error == LIBRAW_SUCCESS && result ? asBitmap(env, *result) : nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_kai_photomark_RawDecoder_nativePreview(JNIEnv* env, jobject, jint fd) {
    try {
        MappedFile input(fd);
        LibRaw raw;
        if (!open(input, raw)) return nullptr;
        const auto& thumbs = raw.imgdata.thumbs_list;
        int best = -1;
        unsigned long long pixels = 0;
        for (int index = 0; index < thumbs.thumbcount; ++index) {
            const auto& entry = thumbs.thumblist[index];
            const auto area = static_cast<unsigned long long>(entry.twidth) * entry.theight;
            if (entry.tlength <= 32 * 1024 * 1024 && area >= pixels && area <= 40000000ULL) {
                best = index;
                pixels = area;
            }
        }
        const int status = best >= 0 ? raw.unpack_thumb_ex(best) : raw.unpack_thumb();
        if (status != LIBRAW_SUCCESS) return nullptr;
        int error = LIBRAW_SUCCESS;
        std::unique_ptr<libraw_processed_image_t, RawImageDeleter> result(raw.dcraw_make_mem_thumb(&error));
        if (error != LIBRAW_SUCCESS || !result || result->type != LIBRAW_IMAGE_JPEG ||
            result->data_size > 32 * 1024 * 1024) return nullptr;
        auto bytes = env->NewByteArray(result->data_size);
        if (bytes) {
            env->SetByteArrayRegion(bytes, 0, result->data_size, reinterpret_cast<const jbyte*>(result->data));
        }
        return bytes;
    } catch (...) {
        return nullptr;
    }
}
