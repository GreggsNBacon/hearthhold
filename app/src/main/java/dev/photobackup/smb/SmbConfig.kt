package dev.photobackup.smb

/** Null [username] means guest. */
data class SmbConfig(
    val host: String,
    val share: String,
    val username: String?,
    val password: String?,
)
