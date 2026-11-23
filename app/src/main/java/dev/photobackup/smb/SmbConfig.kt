package dev.photobackup.smb

/** Null [username] means guest. */
data class SmbConfig(
    val host: String,
    val share: String,
    val username: String?,
    val password: String?,
) {
    /** Never prints the password (logs, crash reports, test failures). */
    override fun toString() = "SmbConfig(host=$host, share=$share, username=$username, password=${if (password == null) "null" else "***"})"
}
