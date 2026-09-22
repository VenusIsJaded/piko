/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.distractionFree.doubleTap

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

// 447: the old onFling(MotionEvent,MotionEvent,FF)+IF_EQZ,IF_EQZ fingerprint matches
// 75 classes and Morphe binds whichever it walks first (usually one without an
// onDoubleTap sibling), so `first { it.name == "onDoubleTap" }` threw
// "Collection contains no element matching the predicate".
// LX/3Rn is the Clips (reels) gesture detector; its onDoubleTap carries a unique
// purge string. Fingerprint it directly and patch it, no sibling lookup.
// Verified against 447.0.0.55.81 (385311944:
// LX/3Rn;->onDoubleTap(Landroid/view/MotionEvent;)Z is the only method with it).
internal object ReelOnDoubleTapFingerprint : Fingerprint(
    name = "onDoubleTap",
    strings = listOf("ClipsItemGestureDetector_onDoubleTap"),
)

@Suppress("unused")
val disableDoubleTapOnReelPatch =
    bytecodePatch(
        description = "Disable double tap like on reels",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {

            ReelOnDoubleTapFingerprint.method.apply {
                addInstructions(
                    0,
                    DOUBLE_TAP_PREF_DESCRIPTOR.format("disableDoubleTapReel"),
                )
            }
        }
    }
