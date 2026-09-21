/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.entity.videoData

import app.crimera.patches.instagram.utils.Constants.ENTITY_CLASS
import app.morphe.patcher.Fingerprint

internal const val EXTENSION_CLASS_DESCRIPTOR = "$ENTITY_CLASS/VideoData;"

internal object ImmutablePandoVideoVersionMapExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
    name = "immutablePandoVideoVersionMap",
)

internal object VideoVersionMapExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
    name = "videoVersionMap",
)

// Matched on the class name alone: the video version models moved from
// `com.instagram.model.mediasize` to `com.instagram.api.schemas` in 447, and both
// names are unique app-wide, so pinning the package only invites another break.
internal object ImmutablePandoVideoVersionMapperFingerprint : Fingerprint(
    definingClass = "/ImmutablePandoVideoVersion;",
    returnType = "Ljava/util/Map;",
)

internal object VideoVersionMapperFingerprint : Fingerprint(
    definingClass = "/VideoVersion;",
    returnType = "Ljava/util/Map;",
)
