#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <unordered_map>

namespace {
constexpr const char* TAG = "ShahbounNative";
std::mutex gMutex;
std::unordered_map<std::string, std::string> gRoots;

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring utf8ToJstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string normalize(std::string path) {
    if (path.empty()) return path;
    std::replace(path.begin(), path.end(), '\\', '/');
    std::string out;
    out.reserve(path.size());
    bool slash = false;
    for (char c : path) {
        if (c == '/') {
            if (!slash) out.push_back(c);
            slash = true;
        } else {
            out.push_back(c);
            slash = false;
        }
    }
    while (out.size() > 1 && out.back() == '/') out.pop_back();
    return out;
}

bool containsTraversal(const std::string& path) {
    if (path == ".." || path.rfind("../", 0) == 0) return true;
    if (path.find("/../") != std::string::npos) return true;
    if (path.size() >= 3 && path.compare(path.size() - 3, 3, "/..") == 0) return true;
    return false;
}

std::string keyFor(const std::string& packageName, jint slot) {
    return packageName + "#" + std::to_string(slot);
}

std::string mapGuestPath(const std::string& packageName, jint slot, const std::string& rawPath) {
    const std::string path = normalize(rawPath);
    if (path.empty() || containsTraversal(path)) return {};

    std::string root;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        const auto it = gRoots.find(keyFor(packageName, slot));
        if (it == gRoots.end()) return path;
        root = it->second;
    }

    const std::string dataUser = "/data/user/0/" + packageName;
    const std::string dataData = "/data/data/" + packageName;
    const std::string userDe = "/data/user_de/0/" + packageName;

    auto remap = [&](const std::string& prefix, const std::string& target) -> std::string {
        if (path == prefix) return normalize(root + "/" + target);
        const std::string withSlash = prefix + "/";
        if (path.rfind(withSlash, 0) == 0) {
            return normalize(root + "/" + target + "/" + path.substr(withSlash.size()));
        }
        return {};
    };

    if (auto mapped = remap(dataUser, "data"); !mapped.empty()) return mapped;
    if (auto mapped = remap(dataData, "data"); !mapped.empty()) return mapped;
    if (auto mapped = remap(userDe, "device_data"); !mapped.empty()) return mapped;
    return path;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeRegisterRoot(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring rootPath) {
    const std::string pkg = jstringToUtf8(env, packageName);
    const std::string root = normalize(jstringToUtf8(env, rootPath));
    if (pkg.empty() || root.empty() || containsTraversal(root)) return JNI_FALSE;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        gRoots[keyFor(pkg, slot)] = root;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "registered %s/%d -> %s", pkg.c_str(), slot, root.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeUnregisterRoot(
    JNIEnv* env, jclass, jstring packageName, jint slot) {
    const std::string pkg = jstringToUtf8(env, packageName);
    std::lock_guard<std::mutex> lock(gMutex);
    gRoots.erase(keyFor(pkg, slot));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeMapGuestPath(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring path) {
    const std::string pkg = jstringToUtf8(env, packageName);
    const std::string raw = jstringToUtf8(env, path);
    return utf8ToJstring(env, mapGuestPath(pkg, slot, raw));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeIsSafePath(
    JNIEnv* env, jclass, jstring path) {
    const std::string raw = normalize(jstringToUtf8(env, path));
    return (!raw.empty() && !containsTraversal(raw)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Shahboun native runtime loaded");
    return JNI_VERSION_1_6;
}
