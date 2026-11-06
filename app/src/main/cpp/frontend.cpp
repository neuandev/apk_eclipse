// Frontend libretro mínimo para Android: carrega um core (.so), roda frames,
// desenha direto em uma ANativeWindow e entrega áudio ao Kotlin via ring buffer.
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <atomic>
#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include "libretro_min.h"

#define TAG "EclipseFrontend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Core {
    void* handle = nullptr;
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    void (*get_system_info)(retro_system_info*) = nullptr;
    void (*get_system_av_info)(retro_system_av_info*) = nullptr;
    void (*reset)() = nullptr;
    void (*run)() = nullptr;
    size_t (*serialize_size)() = nullptr;
    bool (*serialize)(void*, size_t) = nullptr;
    bool (*unserialize)(const void*, size_t) = nullptr;
    bool (*load_game)(const retro_game_info*) = nullptr;
    void (*unload_game)() = nullptr;
    void* (*get_memory_data)(unsigned) = nullptr;
    size_t (*get_memory_size)(unsigned) = nullptr;
};

Core core;
bool gameLoaded = false;
std::string systemDir, saveDir;
unsigned pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
retro_system_av_info avInfo{};

std::mutex winMutex;
ANativeWindow* window = nullptr;
unsigned lastW = 0, lastH = 0;
std::vector<uint32_t> frameBuf;

std::mutex audioMutex;
std::vector<int16_t> audioBuf;
const size_t kMaxAudioSamples = 48000 * 2;  // ~1 s estéreo

std::atomic<uint32_t> buttonMask{0};

void logCb(retro_log_level level, const char* fmt, ...) {
    char line[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    int prio = level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : (level == RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG);
    __android_log_print(prio, "EclipseCore", "%s", line);
}

bool envCb(unsigned cmd, void* data) {
    switch (cmd & 0xFFFF) {  // ignora os bits EXPERIMENTAL/PRIVATE
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            unsigned fmt = *static_cast<const unsigned*>(data);
            if (fmt > RETRO_PIXEL_FORMAT_RGB565) return false;
            pixelFormat = fmt;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            *static_cast<const char**>(data) = systemDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = saveDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = logCb;
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE:
            static_cast<retro_variable*>(data)->value = nullptr;
            return false;  // usa os valores padrão do core
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            return true;
        default:
            return false;
    }
}

void present(const uint32_t* px, unsigned w, unsigned h) {
    std::lock_guard<std::mutex> lock(winMutex);
    if (!window) return;
    if (w != lastW || h != lastH) {
        ANativeWindow_setBuffersGeometry(window, w, h, WINDOW_FORMAT_RGBA_8888);
        lastW = w;
        lastH = h;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(window, &buf, nullptr) != 0) return;
    uint32_t* dst = static_cast<uint32_t*>(buf.bits);
    unsigned rows = std::min<unsigned>(h, buf.height);
    unsigned cols = std::min<unsigned>(w, buf.width);
    for (unsigned y = 0; y < rows; y++) memcpy(dst + (size_t)y * buf.stride, px + (size_t)y * w, cols * 4);
    ANativeWindow_unlockAndPost(window);
}

void videoCb(const void* data, unsigned w, unsigned h, size_t pitch) {
    if (!data || w == 0 || h == 0) return;  // frame duplicado: mantém o anterior
    frameBuf.resize((size_t)w * h);
    const uint8_t* src = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < h; y++) {
        uint32_t* out = &frameBuf[(size_t)y * w];
        const uint8_t* row = src + y * pitch;
        if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t* p = reinterpret_cast<const uint32_t*>(row);
            for (unsigned x = 0; x < w; x++) {
                uint32_t v = p[x];
                uint32_t r = (v >> 16) & 0xFF, g = (v >> 8) & 0xFF, b = v & 0xFF;
                out[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        } else if (pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t* p = reinterpret_cast<const uint16_t*>(row);
            for (unsigned x = 0; x < w; x++) {
                uint32_t v = p[x];
                uint32_t r = (v >> 11) & 0x1F, g = (v >> 5) & 0x3F, b = v & 0x1F;
                r = (r << 3) | (r >> 2);
                g = (g << 2) | (g >> 4);
                b = (b << 3) | (b >> 2);
                out[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        } else {  // 0RGB1555
            const uint16_t* p = reinterpret_cast<const uint16_t*>(row);
            for (unsigned x = 0; x < w; x++) {
                uint32_t v = p[x];
                uint32_t r = (v >> 10) & 0x1F, g = (v >> 5) & 0x1F, b = v & 0x1F;
                r = (r << 3) | (r >> 2);
                g = (g << 3) | (g >> 2);
                b = (b << 3) | (b >> 2);
                out[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        }
    }
    present(frameBuf.data(), w, h);
}

void pushAudio(const int16_t* data, size_t samples) {
    std::lock_guard<std::mutex> lock(audioMutex);
    if (audioBuf.size() + samples > kMaxAudioSamples) {
        size_t drop = audioBuf.size() + samples - kMaxAudioSamples;
        audioBuf.erase(audioBuf.begin(), audioBuf.begin() + std::min(drop, audioBuf.size()));
    }
    audioBuf.insert(audioBuf.end(), data, data + samples);
}

void audioSampleCb(int16_t l, int16_t r) {
    int16_t s[2] = {l, r};
    pushAudio(s, 2);
}

size_t audioBatchCb(const int16_t* data, size_t frames) {
    pushAudio(data, frames * 2);
    return frames;
}

void inputPollCb() {}

int16_t inputStateCb(unsigned port, unsigned device, unsigned /*index*/, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) return 0;
    uint32_t m = buttonMask.load();
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(m & 0xFFFF);
    return (m >> id) & 1;
}

template <typename T>
bool sym(T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(core.handle, name));
    if (!out) LOGE("símbolo ausente: %s", name);
    return out != nullptr;
}

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    out.resize(n > 0 ? (size_t)n : 0);
    size_t got = n > 0 ? fread(out.data(), 1, out.size(), f) : 0;
    fclose(f);
    return got == out.size();
}

bool writeFile(const std::string& path, const void* data, size_t size) {
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return false;
    size_t w = fwrite(data, 1, size, f);
    fclose(f);
    return w == size;
}

}  // namespace

extern "C" {

#define JFN(ret, name) JNIEXPORT ret JNICALL Java_com_neuan_eclipse_NativeBridge_##name

JFN(jboolean, loadCore)(JNIEnv* env, jobject, jstring path) {
    core = Core{};
    core.handle = dlopen(str(env, path).c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!core.handle) {
        LOGE("dlopen falhou: %s", dlerror());
        return JNI_FALSE;
    }
    bool ok = sym(core.set_environment, "retro_set_environment") &&
              sym(core.set_video_refresh, "retro_set_video_refresh") &&
              sym(core.set_audio_sample, "retro_set_audio_sample") &&
              sym(core.set_audio_sample_batch, "retro_set_audio_sample_batch") &&
              sym(core.set_input_poll, "retro_set_input_poll") &&
              sym(core.set_input_state, "retro_set_input_state") &&
              sym(core.init, "retro_init") && sym(core.deinit, "retro_deinit") &&
              sym(core.get_system_info, "retro_get_system_info") &&
              sym(core.get_system_av_info, "retro_get_system_av_info") &&
              sym(core.reset, "retro_reset") && sym(core.run, "retro_run") &&
              sym(core.serialize_size, "retro_serialize_size") && sym(core.serialize, "retro_serialize") &&
              sym(core.unserialize, "retro_unserialize") && sym(core.load_game, "retro_load_game") &&
              sym(core.unload_game, "retro_unload_game") &&
              sym(core.get_memory_data, "retro_get_memory_data") &&
              sym(core.get_memory_size, "retro_get_memory_size");
    if (!ok) {
        dlclose(core.handle);
        core = Core{};
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JFN(jboolean, loadGame)(JNIEnv* env, jobject, jstring rom, jstring sysDir, jstring svDir) {
    if (!core.handle) return JNI_FALSE;
    systemDir = str(env, sysDir);
    saveDir = str(env, svDir);
    std::string romPath = str(env, rom);

    pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    buttonMask = 0;
    audioBuf.clear();
    lastW = lastH = 0;

    core.set_environment(envCb);
    core.init();
    core.set_video_refresh(videoCb);
    core.set_audio_sample(audioSampleCb);
    core.set_audio_sample_batch(audioBatchCb);
    core.set_input_poll(inputPollCb);
    core.set_input_state(inputStateCb);

    retro_system_info info{};
    core.get_system_info(&info);

    std::vector<uint8_t> data;
    retro_game_info game{};
    game.path = romPath.c_str();
    if (!info.need_fullpath) {
        if (!readFile(romPath, data)) {
            LOGE("não consegui ler a ROM: %s", romPath.c_str());
            core.deinit();
            return JNI_FALSE;
        }
        game.data = data.data();
        game.size = data.size();
    }
    if (!core.load_game(&game)) {
        LOGE("retro_load_game falhou");
        core.deinit();
        return JNI_FALSE;
    }
    core.get_system_av_info(&avInfo);
    gameLoaded = true;
    return JNI_TRUE;
}

JFN(jint, getWidth)(JNIEnv*, jobject) { return (jint)avInfo.geometry.base_width; }
JFN(jint, getHeight)(JNIEnv*, jobject) { return (jint)avInfo.geometry.base_height; }
JFN(jfloat, getAspect)(JNIEnv*, jobject) { return (jfloat)avInfo.geometry.aspect_ratio; }
JFN(jdouble, getFps)(JNIEnv*, jobject) { return (jdouble)avInfo.timing.fps; }
JFN(jdouble, getSampleRate)(JNIEnv*, jobject) { return (jdouble)avInfo.timing.sample_rate; }

JFN(void, run)(JNIEnv*, jobject) {
    if (gameLoaded) core.run();
}

JFN(jint, readAudio)(JNIEnv* env, jobject, jshortArray out, jint max) {
    std::lock_guard<std::mutex> lock(audioMutex);
    jint n = (jint)std::min<size_t>(audioBuf.size(), (size_t)max) & ~1;  // sempre pares (estéreo)
    if (n > 0) {
        env->SetShortArrayRegion(out, 0, n, audioBuf.data());
        audioBuf.erase(audioBuf.begin(), audioBuf.begin() + n);
    }
    return n;
}

JFN(void, setButtons)(JNIEnv*, jobject, jint mask) { buttonMask = (uint32_t)mask; }

JFN(void, setSurface)(JNIEnv* env, jobject, jobject surface) {
    std::lock_guard<std::mutex> lock(winMutex);
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
    if (surface) window = ANativeWindow_fromSurface(env, surface);
    lastW = lastH = 0;
}

JFN(jboolean, saveState)(JNIEnv* env, jobject, jstring path) {
    if (!gameLoaded) return JNI_FALSE;
    size_t size = core.serialize_size();
    if (size == 0) return JNI_FALSE;
    std::vector<uint8_t> buf(size);
    if (!core.serialize(buf.data(), size)) return JNI_FALSE;
    return writeFile(str(env, path), buf.data(), size) ? JNI_TRUE : JNI_FALSE;
}

JFN(jboolean, loadState)(JNIEnv* env, jobject, jstring path) {
    if (!gameLoaded) return JNI_FALSE;
    std::vector<uint8_t> buf;
    if (!readFile(str(env, path), buf) || buf.empty()) return JNI_FALSE;
    return core.unserialize(buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
}

JFN(jboolean, saveSram)(JNIEnv* env, jobject, jstring path) {
    if (!gameLoaded) return JNI_FALSE;
    void* data = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return JNI_FALSE;
    return writeFile(str(env, path), data, size) ? JNI_TRUE : JNI_FALSE;
}

JFN(jboolean, loadSram)(JNIEnv* env, jobject, jstring path) {
    if (!gameLoaded) return JNI_FALSE;
    void* data = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    std::vector<uint8_t> buf;
    if (!data || size == 0 || !readFile(str(env, path), buf)) return JNI_FALSE;
    memcpy(data, buf.data(), std::min(size, buf.size()));
    return JNI_TRUE;
}

JFN(void, reset)(JNIEnv*, jobject) {
    if (gameLoaded) core.reset();
}

JFN(void, unload)(JNIEnv*, jobject) {
    if (gameLoaded) {
        core.unload_game();
        gameLoaded = false;
    }
    if (core.handle) {
        core.deinit();
        dlclose(core.handle);
        core = Core{};
    }
}

}  // extern "C"
