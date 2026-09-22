/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.comment.saveMediaComment

import app.crimera.patches.instagram.entity.commentDataEntity.CHAT_CONTEXT_BUTTON_SUPER_CLASS
import app.crimera.patches.instagram.utils.Constants.COMMENT_BUTTON_EXTENSION_CLASS
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId

internal const val COMMENT_COPY_EXTENSION_CLASS = "${COMMENT_BUTTON_EXTENSION_CLASS}/saveMediaButton"
internal const val BUTTON_EXTENSION_CLASS = "${COMMENT_COPY_EXTENSION_CLASS}/SaveMediaButton;"

internal const val INIT_BUTTON_EXTENSION_CLASS = "${COMMENT_COPY_EXTENSION_CLASS}/InitSaveMediaButton;"

internal object InitSaveMediaButtonInitExtensionFingerprint : Fingerprint(
    name = "<init>",
    definingClass = INIT_BUTTON_EXTENSION_CLASS,
)

internal object InitSaveMediaButtonExtensionFingerprint : Fingerprint(
    name = "<init>",
    definingClass = BUTTON_EXTENSION_CLASS,
)

// 447 moves many string constants out of the method that uses them and into an obfuscated string
// pool (`LX/000;->A00(I)Ljava/lang/String;`) keyed by an int. The SaveMedia button's `toString` is:
//
//     const/16 v0, 0x444                                  <- 1092
//     invoke-static LX/000;->A00(I)Ljava/lang/String;
//     return-object
//
// The "SaveMedia" literal is therefore no longer present in the method, so matching on it found
// nothing and the patch threw. The sibling buttons that still resolve (CopyText, ViewSources, ...)
// simply have not been moved into the pool yet, which is why only this one regressed.
//
// The pool key is a build-specific index, so keying off 1092 would break on the next rebuild.
// Identify the button by its constructor instead, which is stable and self-describing:
//
//     LX/L0g;-><init>()V
//         sget-object   LX/MWB;->A0y:LX/MWB;
//         const         2131239738     <- R.drawable.instagram_download_outline_24
//         const         2131964252     <- the button label, read by saveMediaCommentPatch
//         const/4       0
//         invoke-direct LX/PCY;-><init>(LX/MWB;,I,I,Z)V
//
// Of the 23 subclasses of the chat-button base class, `LX/L0g;` is the only one whose constructor
// loads the download drawable -- the same drawable `saveMediaCommentPatch` uses for the button it
// injects. Across the whole APK it is also the only no-arg constructor referencing that drawable.
//
// This fingerprint matches the constructor directly, so the separate `toString` anchor that used
// to resolve the class is no longer needed.
internal object SaveMediaChatButtonInitFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(),
    custom = { _, classDef ->
        classDef.superclass == CHAT_CONTEXT_BUTTON_SUPER_CLASS
    },
    filters =
        listOf(
            literal(getResourceId(ResourceType.DRAWABLE, "instagram_download_outline_24")),
        ),
)
