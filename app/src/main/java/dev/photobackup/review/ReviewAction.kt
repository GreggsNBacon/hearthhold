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

/** The name shown for an action in review hints and the Settings dropdowns. */
val ReviewAction.label: String
    get() = when (this) {
        ReviewAction.KEEP -> "Keep"
        ReviewAction.DRIVE_ONLY -> "Drive only"
        ReviewAction.JUNK -> "Junk"
        ReviewAction.SKIP -> "Skip"
    }

/** Settings label for a gesture slot; null = "No action". */
fun gestureLabel(action: ReviewAction?): String = action?.label ?: "No action"

val Swipe.label: String
    get() = when (this) {
        Swipe.UP -> "Swipe up"
        Swipe.RIGHT -> "Swipe right"
        Swipe.LEFT -> "Swipe left"
        Swipe.DOWN -> "Swipe down"
    }
