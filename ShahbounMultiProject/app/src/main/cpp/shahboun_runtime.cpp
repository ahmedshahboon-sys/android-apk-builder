#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr const char* TAG = "ShahbounNative";
std::mutex gMutex;

struct RootRecord {
    std::string packageName;
    int slot = -1;
    std::string root;
};

std::unordered_map<std::string, RootRecord> gRoots;

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

std::string normalizeLexical(std::string path) {
    if (path.empty()) return path;
    std::replace(path.begin(), path.end(), '\\', '/');
    const bool absolute = path.front() == '/';
    std::vector<std::string> parts;
    std::string current;
    auto flush = [&]() {
        if (current.empty() || current == ".") {
            current.clear();
            return;
        }
        if (current == "..") {
            if (!parts.empty() && parts.back() != "..") parts.pop_back();
            else if (!absolute) parts.push_back(current);
        } else {
            parts.push_back(current);
        }
        current.clear();
    };
    for (char c : path) {
        if (c == '/') flush(); else current.push_back(c);
    }
    flush();

    std::string out = absolute ? "/" : "";
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i > 0) out.push_back('/');
        out += parts[i];
    }
    if (out.empty()) return absolute ? "/" : ".";
    return out;
}

bool isAbsoluteSafe(const std::string& raw) {
    if (raw.empty()) return false;
    const auto normalized = normalizeLexical(raw);
    return !normalized.empty() && normalized.front() == '/' && normalized.find('\0') == std::string::npos;
}

std::string keyFor(const std::string& packageName, jint slot) {
    return packageName + "#" + std::to_string(slot);
}

bool startsWithPath(const std::string& path, const std::string& prefix) {
    if (path == prefix) return true;
    return path.size() > prefix.size() && path.compare(0, prefix.size(), prefix) == 0 && path[prefix.size()] == '/';
}

bool isWithinRoot(const std::string& path, const std::string& root) {
    return startsWithPath(normalizeLexical(path), normalizeLexical(root));
}

std::string suffixAfter(const std::string& path, const std::string& prefix) {
    if (path == prefix) return {};
    if (!startsWithPath(path, prefix)) return {};
    return path.substr(prefix.size() + 1);
}

std::string joinRoot(const std::string& root, const std::string& bucket, const std::string& suffix) {
    std::string out = normalizeLexical(root + "/" + bucket);
    if (!suffix.empty()) out = normalizeLexical(out + "/" + suffix);
    return out;
}

bool lookupRecord(const std::string& packageName, jint slot, RootRecord* out) {
    std::lock_guard<std::mutex> lock(gMutex);
    const auto it = gRoots.find(keyFor(packageName, slot));
    if (it == gRoots.end()) return false;
    *out = it->second;
    return true;
}

std::string mapGuestPath(const std::string& packageName, jint slot, const std::string& rawPath) {
    const std::string path = normalizeLexical(rawPath);
    if (path.empty()) return {};

    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return path;

    struct Rule { std::string guest; const char* bucket; };
    const std::vector<Rule> rules = {
        {"/data/user/0/" + packageName, "data"},
        {"/data/data/" + packageName, "data"},
        {"/data/user_de/0/" + packageName, "device_data"},
        {"/storage/emulated/0/Android/data/" + packageName, "external"},
        {"/sdcard/Android/data/" + packageName, "external"},
        {"/storage/emulated/0/Android/media/" + packageName, "external/media"},
        {"/sdcard/Android/media/" + packageName, "external/media"},
        {"/storage/emulated/0/Android/obb/" + packageName, "external/obb"},
        {"/sdcard/Android/obb/" + packageName, "external/obb"}
    };

    for (const auto& rule : rules) {
        if (!startsWithPath(path, rule.guest)) continue;
        const auto mapped = joinRoot(record.root, rule.bucket, suffixAfter(path, rule.guest));
        if (!isWithinRoot(mapped, record.root)) return {};
        return mapped;
    }
    return path;
}

std::string reverseGuestPath(const std::string& packageName, jint slot, const std::string& rawPath) {
    const std::string path = normalizeLexical(rawPath);
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return path;
    if (!isWithinRoot(path, record.root)) return path;

    struct ReverseRule { const char* bucket; std::string guest; };
    const std::vector<ReverseRule> rules = {
        {"device_data", "/data/user_de/0/" + packageName},
        {"external/media", "/storage/emulated/0/Android/media/" + packageName},
        {"external/obb", "/storage/emulated/0/Android/obb/" + packageName},
        {"external", "/storage/emulated/0/Android/data/" + packageName},
        {"data", "/data/user/0/" + packageName}
    };

    for (const auto& rule : rules) {
        const auto physical = normalizeLexical(record.root + "/" + rule.bucket);
        if (!startsWithPath(path, physical)) continue;
        const auto suffix = suffixAfter(path, physical);
        return suffix.empty() ? rule.guest : normalizeLexical(rule.guest + "/" + suffix);
    }
    return path;
}

std::string describePolicy(const std::string& packageName, jint slot) {
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return "UNREGISTERED";
    return "PATH_MAP_V2 root=" + record.root + " internal=data,device_data external=data,media,obb reverse=true traversal=lexical-contained syscall_intercept=false";
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeRegisterRoot(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring rootPath) {
    const std::string pkg = jstringToUtf8(env, packageName);
    const std::string root = normalizeLexical(jstringToUtf8(env, rootPath));
    if (pkg.empty() || slot < 0 || !isAbsoluteSafe(root)) return JNI_FALSE;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        gRoots[keyFor(pkg, slot)] = RootRecord{pkg, slot, root};
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
    return utf8ToJstring(env, mapGuestPath(jstringToUtf8(env, packageName), slot, jstringToUtf8(env, path)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeReverseMapGuestPath(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring path) {
    return utf8ToJstring(env, reverseGuestPath(jstringToUtf8(env, packageName), slot, jstringToUtf8(env, path)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeDescribePolicy(
    JNIEnv* env, jclass, jstring packageName, jint slot) {
    return utf8ToJstring(env, describePolicy(jstringToUtf8(env, packageName), slot));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeIsSafePath(
    JNIEnv* env, jclass, jstring path) {
    return isAbsoluteSafe(jstringToUtf8(env, path)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Shahboun native runtime loaded path-map-v2");
    return JNI_VERSION_1_6;
}
