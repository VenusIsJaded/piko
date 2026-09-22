/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.comment

import app.crimera.patches.instagram.utils.Constants.COMMENT_BUTTON_EXTENSION_CLASS
import app.morphe.patcher.Fingerprint

internal const val HANDLE_COMMENT_BUTTON_EXTENSION_CLASS = "${COMMENT_BUTTON_EXTENSION_CLASS}/HandleCommentButton;"

// The builder returns the concrete `ArrayList` on 447, not the `List` interface, so pinning the
// exact return type matched nothing and this fingerprint threw. The string already identifies
// the method uniquely (it appears in exactly one method in the APK), so drop the return type
// rather than swapping in another concrete type a later rebuild can change again.
internal object AddCommentButtonFingerprint : Fingerprint(
    strings = listOf("instagram_share_comment_to_story_entrypoint_impression"),
)
