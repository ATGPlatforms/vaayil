package dev.vaayil.core.data

/** 32 random bytes as base64url without padding: 43 characters, safe inside adb shell quoting. */
fun generateExternalControlToken(): String {
    val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
