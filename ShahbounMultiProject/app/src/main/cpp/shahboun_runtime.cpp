#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cctype>
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

// JNI's GetStringUTFChars uses modified UTF-8, where Java U+0000 is encoded as C0 80 rather than
// a C NUL. Explicitly inspect UTF-16 first so NUL/path injection cannot bypass lexical validation.
bool jstringHasJavaNul(JNIEnv* env, jstring value) {
    if (value == nullptr) return false;
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return true;
    bool found = false;
    for (jsize i = 0; i < length; ++i) {
        if (chars[i] == 0) { found = true; break; }
    }
    env->ReleaseStringChars(value, chars);
    return found;
}

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr || jstringHasJavaNul(env, value)) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring utf8ToJstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

bool containsNul(const std::string& value) {
    return std::find(value.begin(), value.end(), '\0') != value.end();
}

bool safePackageName(const std::string& packageName) {
    if (packageName.empty() || packageName.size() > 255 || containsNul(packageName)) return false;
    if (packageName.front() == '.' || packageName.back() == '.' || packageName.find("..") != std::string::npos) return false;
    return std::all_of(packageName.begin(), packageName.end(), [](unsigned char c) {
        return std::isalnum(c) || c == '.' || c == '_';
    });
}

std::string normalizeSeparators(std::string path) {
    // Backslash is not a Linux path separator. Treating it as one would give Java and native callers
    // different path semantics; reject it at managed-boundary checks instead of silently rewriting.
    while (path.find("//") != std::string::npos) path.replace(path.find("//"), 2, "/");
    return path;
}

bool malformedPath(const std::string& path) {
    return path.empty() || containsNul(path) || path.find('\\') != std::string::npos;
}

std::string normalizeLexical(std::string path) {
    if (malformedPath(path)) return {};
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
    if (malformedPath(raw)) return false;
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
    const auto p = normalizeLexical(path);
    const auto r = normalizeLexical(root);
    return !p.empty() && !r.empty() && startsWithPath(p, r);
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
    if (!safePackageName(packageName) || slot < 0 || out == nullptr) return false;
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

bool rawManagedPathEscapes(const std::string& raw, const std::string& guestRoot) {
    if (malformedPath(raw)) return true;
    if (!startsWithPath(raw, guestRoot)) return false;
    const auto normalized = normalizeLexical(raw);
    return normalized.empty() || !startsWithPath(normalized, guestRoot);
}

std::string mapGuestPath(const std::string& packageName, jint slot, const std::string& rawPath) {
    if (!safePackageName(packageName) || slot < 0 || malformedPath(rawPath)) return {};
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return normalizeLexical(rawPath);

    const std::string raw = normalizeSeparators(rawPath);
    const std::string path = normalizeLexical(rawPath);
    if (path.empty()) return {};
    const auto rules = rulesFor(packageName);

    for (const auto& rule : rules) {
        if (!startsWithPath(raw, rule.guest)) continue;
        if (rawManagedPathEscapes(raw, rule.guest)) return {};
        const auto mapped = joinRoot(record.root, rule.bucket, suffixAfter(path, rule.guest));
        if (!isWithinRoot(mapped, record.root)) return {};
        return mapped;
    }
    return path;
}

std::string resolveRelativeLogical(const std::string& baseLogical, const std::string& relative) {
    if (malformedPath(relative)) return {};
    if (relative.front() == '/') return normalizeLexical(relative);
    const auto base = normalizeLexical(baseLogical);
    if (base.empty() || base.front() != '/') return {};
    return normalizeLexical(base + "/" + relative);
}

std::string mapGuestPathAt(const std::string& packageName, jint slot,
                           const std::string& dirLogicalPath, const std::string& rawPath) {
    if (!safePackageName(packageName) || slot < 0 || malformedPath(rawPath) || malformedPath(dirLogicalPath)) return {};
    if (rawPath.front() == '/') return mapGuestPath(packageName, slot, rawPath);
    const auto resolved = resolveRelativeLogical(dirLogicalPath, rawPath);
    if (resolved.empty()) return {};

    const auto rules = rulesFor(packageName);
    const auto base = normalizeLexical(dirLogicalPath);
    const auto rawCombined = normalizeSeparators(base + "/" + rawPath);
    for (const auto& rule : rules) {
        if (!startsWithPath(base, rule.guest)) continue;
        if (!startsWithPath(resolved, rule.guest)) return {};
        if (rawManagedPathEscapes(rawCombined, rule.guest)) return {};
        return mapGuestPath(packageName, slot, resolved);
    }
    return mapGuestPath(packageName, slot, resolved);
}

std::string reverseGuestPath(const std::string& packageName, jint slot, const std::string& rawPath) {
    if (!safePackageName(packageName) || slot < 0 || malformedPath(rawPath)) return {};
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

bool registeredPathIsContained(const std::string& packageName, jint slot, const std::string& rawPath) {
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return false;
    return isAbsoluteSafe(rawPath) && isWithinRoot(rawPath, record.root);
}

std::string describePolicy(const std::string& packageName, jint slot) {
    RootRecord record;
    if (!lookupRecord(packageName, slot, &record)) return "UNREGISTERED";
    return "PATH_MAP_V4 root=" + record.root +
        " internal=data,device_data external=data,media,obb reverse=true traversal=reject-managed-escape" 
        " relative_path=true relative_dirfd=logical-only nul=reject backslash=reject symlink_kernel_guard=false syscall_intercept=false linker_namespace=false";
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeRegisterRoot(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring rootPath) {
    if (jstringHasJavaNul(env, packageName) || jstringHasJavaNul(env, rootPath)) return JNI_FALSE;
    const std::string pkg = jstringToUtf8(env, packageName);
    const std::string root = normalizeLexical(jstringToUtf8(env, rootPath));
    if (!safePackageName(pkg) || slot < 0 || !isAbsoluteSafe(root)) return JNI_FALSE;
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
    if (jstringHasJavaNul(env, packageName)) return;
    const std::string pkg = jstringToUtf8(env, packageName);
    if (!safePackageName(pkg) || slot < 0) return;
    std::lock_guard<std::mutex> lock(gMutex);
    gRoots.erase(keyFor(pkg, slot));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeMapGuestPath(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring path) {
    if (jstringHasJavaNul(env, packageName) || jstringHasJavaNul(env, path)) return utf8ToJstring(env, {});
    return utf8ToJstring(env, mapGuestPath(jstringToUtf8(env, packageName), slot, jstringToUtf8(env, path)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeMapGuestPathAt(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring dirLogicalPath, jstring path) {
    if (jstringHasJavaNul(env, packageName) || jstringHasJavaNul(env, dirLogicalPath) || jstringHasJavaNul(env, path)) return utf8ToJstring(env, {});
    return utf8ToJstring(env, mapGuestPathAt(
        jstringToUtf8(env, packageName), slot, jstringToUtf8(env, dirLogicalPath), jstringToUtf8(env, path)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeReverseMapGuestPath(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring path) {
    if (jstringHasJavaNul(env, packageName) || jstringHasJavaNul(env, path)) return utf8ToJstring(env, {});
    return utf8ToJstring(env, reverseGuestPath(jstringToUtf8(env, packageName), slot, jstringToUtf8(env, path)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeDescribePolicy(
    JNIEnv* env, jclass, jstring packageName, jint slot) {
    if (jstringHasJavaNul(env, packageName)) return utf8ToJstring(env, "INVALID");
    return utf8ToJstring(env, describePolicy(jstringToUtf8(env, packageName), slot));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeIsSafePath(
    JNIEnv* env, jclass, jstring path) {
    if (jstringHasJavaNul(env, path)) return JNI_FALSE;
    return isAbsoluteSafe(jstringToUtf8(env, path)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shahboun_multi_RuntimeNativeRuntime_nativeIsWithinRoot(
    JNIEnv* env, jclass, jstring packageName, jint slot, jstring path) {
    if (jstringHasJavaNul(env, packageName) || jstringHasJavaNul(env, path)) return JNI_FALSE;
    return registeredPathIsContained(jstringToUtf8(env, packageName), slot, jstringToUtf8(env, path)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "Shahboun native runtime loaded path-map-v4 hardened");
    return JNI_VERSION_1_6;
}
