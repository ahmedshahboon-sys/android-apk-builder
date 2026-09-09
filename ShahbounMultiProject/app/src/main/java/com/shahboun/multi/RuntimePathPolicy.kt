package com.shahboun.multi

import java.io.File

/** Pure path rules shared by guest storage and JVM regression tests. */
internal object RuntimePathPolicy {
    fun safeLeaf(value: String): String {
        require(value.isNotEmpty()) { "Empty path leaf is not allowed" }
        require(value != "." && value != "..") { "Traversal path leaf is not allowed" }
        require(!value.contains('/') && !value.contains('\\') && !value.contains('\u0000')) {
            "Path separators/NUL are not allowed in a leaf name"
        }
        val sanitized = value.replace(Regex("[^A-Za-z0-9_. -]"), "_")
        require(sanitized != "." && sanitized != "..") { "Unsafe path leaf" }
        return sanitized
    }

    fun isContained(root: File, candidate: File): Boolean {
        val canonicalRoot = root.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        return canonicalCandidate.path == canonicalRoot.path ||
            canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)
    }

    fun child(root: File, relative: String): File {
        require(relative.isNotBlank()) { "Relative path is empty" }
        require(!relative.contains('\u0000')) { "NUL is not allowed" }
        val parts = relative.replace('\\', '/').split('/').filter { it.isNotBlank() }
        require(parts.isNotEmpty()) { "Relative path is empty" }
        require(parts.none { it == "." || it == ".." }) { "Traversal is not allowed" }
        var out = root
        parts.forEach { out = File(out, safeLeaf(it)) }
        check(isContained(root, out)) { "Path escaped clone root" }
        return out
    }

    /**
     * Resolves an openat-style relative path against an already-authorized directory and rejects
     * lexical/canonical escape. Canonical containment also catches symlink escapes when the symlink exists.
     */
    fun resolveRelativeWithin(root: File, directory: File, relative: String): File {
        require(relative.isNotBlank()) { "Relative path is empty" }
        require(!relative.contains('\u0000')) { "NUL is not allowed" }
        require(!File(relative).isAbsolute) { "Absolute path is not relative" }
        require(isContained(root, directory)) { "Directory escaped clone root" }
        val candidate = File(directory, relative).canonicalFile
        require(isContained(root, candidate)) { "Relative path escaped clone root" }
        return candidate
    }
}
