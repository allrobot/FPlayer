#include <jni.h>

#include <mpv/client.h>

extern "C" {
#include <libavcodec/jni.h>
}

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <pthread.h>
#include <string>
#include <unordered_map>
#include <unordered_set>

namespace {
JavaVM* vm = nullptr;
mpv_handle* handle = nullptr;
std::atomic<bool> stop_events{false};
pthread_t event_thread{};
jobject surface_ref = nullptr;
jobject app_context = nullptr;
jclass native_mpv_class = nullptr;
jmethodID native_event_method = nullptr;
std::mutex event_state_mutex;
std::unordered_map<int64_t, uint64_t> entry_requests;
std::unordered_set<int64_t> loaded_entries;
int64_t current_entry_id = -1;

constexpr jint EVENT_PREPARED = 1;
constexpr jint EVENT_COMPLETED = 2;
constexpr jint EVENT_PREPARE_FAILED = 3;

void notify_native(uint64_t request_id, jint event_type, jint error_code = 0) {
    if (vm == nullptr || native_mpv_class == nullptr || native_event_method == nullptr || request_id == 0) {
        return;
    }
    JNIEnv* env = nullptr;
    bool detach = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        detach = true;
    }
    env->CallStaticVoidMethod(
        native_mpv_class,
        native_event_method,
        static_cast<jlong>(request_id),
        event_type,
        error_code
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (detach) {
        vm->DetachCurrentThread();
    }
}

void remove_request_locked(uint64_t request_id) {
    for (auto it = entry_requests.begin(); it != entry_requests.end();) {
        if (it->second == request_id) {
            if (it->first == current_entry_id) {
                current_entry_id = -1;
            }
            it = entry_requests.erase(it);
        } else {
            ++it;
        }
    }
}

int64_t command_playlist_entry_id(const mpv_event* event) {
    if (event->data == nullptr) {
        return -1;
    }
    const auto* command = static_cast<mpv_event_command*>(event->data);
    const mpv_node& result = command->result;
    if (result.format != MPV_FORMAT_NODE_MAP || result.u.list == nullptr) {
        return -1;
    }
    for (int index = 0; index < result.u.list->num; ++index) {
        const char* key = result.u.list->keys[index];
        const mpv_node& value = result.u.list->values[index];
        if (key != nullptr && std::string(key) == "playlist_entry_id" &&
            value.format == MPV_FORMAT_INT64) {
            return value.u.int64;
        }
    }
    return -1;
}

void* event_loop(void*) {
    while (!stop_events.load(std::memory_order_acquire)) {
        mpv_event* event = mpv_wait_event(handle, 0.25);
        if (event == nullptr || event->event_id == MPV_EVENT_NONE) {
            continue;
        }
        uint64_t request_id = 0;
        jint event_type = 0;
        jint error_code = 0;
        {
            std::lock_guard<std::mutex> lock(event_state_mutex);
            if (event->event_id == MPV_EVENT_START_FILE && event->data != nullptr) {
                const auto* start = static_cast<mpv_event_start_file*>(event->data);
                current_entry_id = start->playlist_entry_id;
            } else if (event->event_id == MPV_EVENT_FILE_LOADED) {
                const auto found = entry_requests.find(current_entry_id);
                if (found != entry_requests.end()) {
                    request_id = found->second;
                    event_type = EVENT_PREPARED;
                } else if (current_entry_id >= 0) {
                    loaded_entries.insert(current_entry_id);
                }
            } else if (event->event_id == MPV_EVENT_END_FILE && event->data != nullptr) {
                const auto* end = static_cast<mpv_event_end_file*>(event->data);
                const auto found = entry_requests.find(end->playlist_entry_id);
                if (found != entry_requests.end()) {
                    request_id = found->second;
                    if (end->reason == MPV_END_FILE_REASON_EOF) {
                        event_type = EVENT_COMPLETED;
                    } else if (end->reason == MPV_END_FILE_REASON_ERROR) {
                        event_type = EVENT_PREPARE_FAILED;
                        error_code = end->error;
                    }
                    entry_requests.erase(found);
                    loaded_entries.erase(end->playlist_entry_id);
                    if (current_entry_id == end->playlist_entry_id) {
                        current_entry_id = -1;
                    }
                }
            } else if (event->event_id == MPV_EVENT_COMMAND_REPLY && event->reply_userdata != 0) {
                request_id = event->reply_userdata;
                if (event->error < 0) {
                    remove_request_locked(request_id);
                    event_type = EVENT_PREPARE_FAILED;
                    error_code = event->error;
                } else {
                    const int64_t entry_id = command_playlist_entry_id(event);
                    if (entry_id >= 0) {
                        entry_requests[entry_id] = request_id;
                        if (loaded_entries.erase(entry_id) > 0) {
                            event_type = EVENT_PREPARED;
                        }
                    }
                }
            }
        }
        if (event_type != 0) {
            notify_native(request_id, event_type, error_code);
        }
    }
    return nullptr;
}

void require_handle() {
    if (handle == nullptr) {
        std::abort();
    }
}

void set_string(const char* property, const std::string& value) {
    require_handle();
    mpv_set_property_string(handle, property, value.c_str());
}

double get_double(const char* property, double fallback) {
    require_handle();
    double value = fallback;
    if (mpv_get_property(handle, property, MPV_FORMAT_DOUBLE, &value) < 0) {
        return fallback;
    }
    return value;
}

bool get_flag(const char* property) {
    require_handle();
    int value = 0;
    return mpv_get_property(handle, property, MPV_FORMAT_FLAG, &value) >= 0 && value != 0;
}

std::string jstring_value(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? std::string() : std::string(chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* java_vm, void*) {
    vm = java_vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_create(JNIEnv* env, jobject, jobject context) {
    if (handle != nullptr) {
        return;
    }
    handle = mpv_create();
    if (handle == nullptr) {
        std::abort();
    }
    app_context = env->NewGlobalRef(context);
    jclass local_native_mpv = env->FindClass("io/github/fplayer/player/mpv/NativeMpv");
    if (local_native_mpv == nullptr) {
        std::abort();
    }
    native_mpv_class = static_cast<jclass>(env->NewGlobalRef(local_native_mpv));
    native_event_method = env->GetStaticMethodID(native_mpv_class, "onNativeEvent", "(JII)V");
    env->DeleteLocalRef(local_native_mpv);
    if (app_context == nullptr || native_mpv_class == nullptr || native_event_method == nullptr ||
        av_jni_set_java_vm(vm, nullptr) < 0 ||
        av_jni_set_android_app_ctx(app_context, nullptr) < 0) {
        std::abort();
    }
    mpv_set_option_string(handle, "config", "no");
    mpv_set_option_string(handle, "terminal", "no");
    mpv_set_option_string(handle, "force-window", "no");
    mpv_set_option_string(handle, "idle", "yes");
    mpv_set_option_string(handle, "vo", "null");
    mpv_set_option_string(handle, "gpu-context", "android");
    mpv_set_option_string(handle, "opengl-es", "yes");
    mpv_set_option_string(handle, "ao", "audiotrack,opensles");
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_initialize(JNIEnv*, jobject) {
    require_handle();
    if (mpv_initialize(handle) < 0) {
        std::abort();
    }
    stop_events.store(false, std::memory_order_release);
    if (pthread_create(&event_thread, nullptr, event_loop, nullptr) != 0) {
        std::abort();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_destroy(JNIEnv* env, jobject) {
    if (handle == nullptr) {
        return;
    }
    stop_events.store(true, std::memory_order_release);
    mpv_wakeup(handle);
    pthread_join(event_thread, nullptr);
    {
        std::lock_guard<std::mutex> lock(event_state_mutex);
        entry_requests.clear();
        loaded_entries.clear();
        current_entry_id = -1;
    }
    if (surface_ref != nullptr) {
        env->DeleteGlobalRef(surface_ref);
        surface_ref = nullptr;
    }
    mpv_terminate_destroy(handle);
    handle = nullptr;
    av_jni_set_android_app_ctx(nullptr, nullptr);
    if (app_context != nullptr) {
        env->DeleteGlobalRef(app_context);
        app_context = nullptr;
    }
    if (native_mpv_class != nullptr) {
        env->DeleteGlobalRef(native_mpv_class);
        native_mpv_class = nullptr;
        native_event_method = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_prepare(
    JNIEnv* env,
    jobject,
    jstring locator,
    jlong resume_ms,
    jlong request_id
) {
    require_handle();
    std::string value = jstring_value(env, locator);
    const std::string start = "start=" + std::to_string(static_cast<double>(resume_ms) / 1000.0);
    const char* command[] = {"loadfile", value.c_str(), "replace", "-1", start.c_str(), nullptr};
    const int result = mpv_command_async(handle, static_cast<uint64_t>(request_id), command);
    if (result < 0) {
        {
            std::lock_guard<std::mutex> lock(event_state_mutex);
            remove_request_locked(static_cast<uint64_t>(request_id));
        }
        notify_native(static_cast<uint64_t>(request_id), EVENT_PREPARE_FAILED, result);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_play(JNIEnv*, jobject) {
    set_string("pause", "no");
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_pause(JNIEnv*, jobject) {
    set_string("pause", "yes");
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_seekTo(JNIEnv*, jobject, jlong position_ms) {
    const std::string position = std::to_string(static_cast<double>(position_ms) / 1000.0);
    const char* command[] = {"seek", position.c_str(), "absolute", nullptr};
    mpv_command_async(handle, 0, command);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_setSpeed(JNIEnv*, jobject, jdouble speed) {
    if (!std::isfinite(speed)) {
        return;
    }
    mpv_set_property(handle, "speed", MPV_FORMAT_DOUBLE, &speed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_positionMs(JNIEnv*, jobject) {
    return static_cast<jlong>(std::max(0.0, get_double("time-pos", 0.0)) * 1000.0);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_durationMs(JNIEnv*, jobject) {
    return static_cast<jlong>(get_double("duration", 0.0) * 1000.0);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_speed(JNIEnv*, jobject) {
    return get_double("speed", 1.0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_isPlaying(JNIEnv*, jobject) {
    return !get_flag("pause") && !get_flag("core-idle") ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_isBuffering(JNIEnv*, jobject) {
    return get_flag("paused-for-cache") ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_attachSurface(JNIEnv* env, jobject, jobject surface) {
    require_handle();
    if (surface_ref != nullptr) {
        env->DeleteGlobalRef(surface_ref);
    }
    surface_ref = env->NewGlobalRef(surface);
    if (surface_ref == nullptr) {
        std::abort();
    }
    int64_t wid = reinterpret_cast<intptr_t>(surface_ref);
    if (mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &wid) < 0) {
        std::abort();
    }
    set_string("vo", "gpu");
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_fplayer_player_mpv_NativeMpv_detachSurface(JNIEnv* env, jobject) {
    require_handle();
    set_string("vo", "null");
    int64_t wid = 0;
    mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &wid);
    if (surface_ref != nullptr) {
        env->DeleteGlobalRef(surface_ref);
        surface_ref = nullptr;
    }
}
