package dev.photobackup.media

data class PhoneMedia(
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val dateTakenMs: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val isVideo: Boolean,
    val mimeType: String,
)

/** What a MediaStore id points to now: enough to tell whether it is still the same file as a ledger row. */
data class MediaIdentity(val displayName: String, val sizeBytes: Long)
