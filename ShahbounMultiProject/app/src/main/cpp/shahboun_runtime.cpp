#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdlib>
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

bool malformed(const std::string& path) {
    if (path.empty()) return false;
    if (path.size() > 4096) return true;
    return path.find('\n') != std::string::npos || path.find('\r') != std::string::npos;
}

std::string normalizeSeparators(std::string path) {
    std::replace(path.begin(), path.end(), '\\', '/');
    while (path.find("//") != std::string::npos) path.replace(path.find("//"), 2, "/");
    return path;
}

std::string normalizeLexical(std::string path) {
    if (path.empty()) return path;
    path = normalizeSeparators(std::move(path));
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
    if (raw.empty() || malformed(raw)) return false;
    const auto normalized = normalizeLexical(raw);
    return !normalized.empty() && normalized.front() == '/';
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

struct MappingRule {
    std::string guest;
    const char* bucket;
};

std::vector<MappingRule> rulesFor(const std::string& packageName) {
    return {
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
}

bool isManagedGuestPath(const std::string& packageName, const std::string& rawPath) {
    const std::string raw = normalizeSeparators(rawPath);
    for (const auto& rule : rulesFor(packageName)) if (startsWithPath(raw, rule.guest)) return true;
    return false;
}

std::string mapGuestPath(const std::string& packageName, jint slot, const std::string& rawPath) {
    if (rawPath.empty() || malformed(rawPath)) return {};
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return normalizeLexical(rawPath);

    const std::string raw = normalizeSeparators(rawPath);
    const std::string path = normalizeLexical(rawPath);
    const auto rules = rulesFor(packageName);

    for (const auto& rule : rules) {
        if (!startsWithPath(raw, rule.guest)) continue;
        if (!startsWithPath(path, rule.guest)) return {};
        const auto mapped = joinRoot(record.root, rule.bucket, suffixAfter(path, rule.guest));
        if (!isWithinRoot(mapped, record.root)) return {};
        return mapped;
    }
    return path;
}

std::string reverseGuestPath(const std::string& packageName, jint slot, const std::string& rawPath) {
    if (rawPath.empty() || malformed(rawPath)) return {};
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

std::string resolveRelativePhysical(const std::string& packageName, jint slot, const std::string& dirBase, const std::string& rawPath) {
    if (rawPath.empty() || malformed(rawPath)) return {};
    if (!rawPath.empty() && rawPath.front() == '/') return mapGuestPath(packageName, slot, rawPath);
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return {};
    const std::string base = normalizeLexical(dirBase);
    if (!isAbsoluteSafe(base) || !isWithinRoot(base, record.root)) return {};
    const std::string combined = normalizeLexical(base + "/" + normalizeSeparators(rawPath));
    if (!isWithinRoot(combined, record.root)) return {};
    return combined;
}

std::string canonicalExisting(const std::string& rawPath) {
    if (!isAbsoluteSafe(rawPath)) return {};
    char resolved[PATH_MAX];
    if (::realpath(rawPath.c_str(), resolved) == nullptr) return {};
    return normalizeLexical(resolved);
}

std::string canonicalParentForCreate(const std::string& rawPath) {
    if (!isAbsoluteSafe(rawPath)) return {};
    const auto path = normalizeLexical(rawPath);
    const auto slash = path.find_last_of('/');
    if (slash == std::string::npos) return {};
    const std::string parent = slash == 0 ? "/" : path.substr(0, slash);
    const std::string leaf = path.substr(slash + 1);
    if (leaf.empty() || leaf == "." || leaf == "..") return {};
    const std::string realParent = canonicalExisting(parent);
    if (realParent.empty()) return {};
    return normalizeLexical(realParent + "/" + leaf);
}

bool registeredPathIsContained(const std::string& packageName, jint slot, const std::string& rawPath) {
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return false;
    return isAbsoluteSafe(rawPath) && isWithinRoot(rawPath, record.root);
}

bool existingPathIsContained(const std::string& packageName, jint slot, const std::string& rawPath) {
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return false;
    const auto canonical = canonicalExisting(rawPath);
    return !canonical.empty() && isWithinRoot(canonical, record.root);
}

bool createTargetIsContained(const std::string& packageName, jint slot, const std::string& rawPath) {
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return false;
    const auto canonical = canonicalParentForCreate(rawPath);
    return !canonical.empty() && isWithinRoot(canonical, record.root);
}

std::string describePolicy(const std::string& packageName, jint slot) {
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return "UNREGISTERED";
    return "PATH_MAP_V4 root=" + record.root +
        " internal=data,device_data external=data,media,obb reverse=true traversal=reject-managed-escape"
        " relative_path=physical-dirbase-contained symlink_existing=realpath-contained create_parent=realpath-contained"
        " syscall_intercept=false linker_namespace=false at_fdcwd_hook=false";
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
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeResolveRelativePath(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring dirBase, jstring path) {
    return utf8ToJstring(env, resolveRelativePhysical(jstringToUtf8(env, packageName), slot, jstringToUtf8(env, dirBase), jstringToUtf8(env, path)));
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeIsWithinRoot(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring path) {
    return registeredPathIsContained(jstringToUtf8(env, packageName), slot, jstringToUtf8(env, path)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeExistingPathContained(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring path) {
    return existingPathIsContained(jstringToUtf8(env, packageName), slot, jstringToUtf8(env, path)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeCreateTargetContained(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring path) {
    return createTargetIsContained(jstringToUtf8(env, packageName), slot, jstringToUtf8(env, path)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Shahboun native runtime loaded path-map-v4");
    return JNI_VERSION_1_6;
}
