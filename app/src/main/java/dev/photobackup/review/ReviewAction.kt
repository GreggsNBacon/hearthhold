package dev.photobackup.review

/** What a swipe decides for a photo during review. Nothing is applied until Finish. */
enum class ReviewAction { KEEP, DRIVE_ONLY, JUNK, SKIP }

enum class Swipe { UP, RIGHT, LEFT, DOWN }

/** Default gesture map: Up = Keep, Right = Drive only, Left = Junk, Down = Skip. */
val DEFAULT_GESTURES: Map<Swipe, ReviewAction?> = mapOf(
    Swipe.UP to ReviewAction.KEEP,
    Swipe.RIGHT to ReviewAction.DRIVE_ONLY,
    Swipe.LEFT to ReviewAction.JUNK,
    Swipe.DOWN to ReviewAction.SKIP,
)
