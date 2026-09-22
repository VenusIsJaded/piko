/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.entity.decoder

import app.crimera.patches.instagram.utils.Constants.EDIT_MEDIA_INFO_FRAGMENT_CLASS
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.Opcode

// Also used to in description extraction in MediaEntity
//
// `decoderEntity` reads the first `iget` of the matched method to learn CURRENT_MEDIA_FIELD,
// so this fingerprint has to land on a method that actually performs one.
//
// Constraining only definingClass + returnType + zero parameters does not do that. On 447 the
// fragment declares two no-arg String methods and *neither* contains a plain `iget`:
//
//     A07()            reads MVLinkInfo/Media, only `iget-object`
//     getModuleName()  `const-string "edit_media_info"; return-object`
//
// Morphe keeps whichever it walks first, so `indexOfFirstInstruction(Opcode.IGET)` returned -1
// and `getInstruction(-1)` threw "Index -1 out of bounds for length 15" from Decoder.kt.
//
// The real getter takes the fragment as a parameter, so `parameters = listOf()` was actively
// excluding it. It is the only String-returning method on the class with a plain `iget`:
//
//     A0A(EditMediaInfoFragment)Ljava/lang/String;
//         iget-object p0, EditMediaInfoFragment;->A0L:LX/6oE;   <- media add info object
//         iget            LX/6oE;->A0A:I                        <- CURRENT_MEDIA_FIELD
//
// Match that shape instead: the add-info `iget-object` read off the fragment, followed by the
// `int` `iget` the patch consumes.
object EditMediaInfoGetCurrentMediaIdFingerprint : Fingerprint(
    definingClass = EDIT_MEDIA_INFO_FRAGMENT_CLASS,
    returnType = "Ljava/lang/String;",
    filters =
        listOf(
            fieldAccess(
                definingClass = EDIT_MEDIA_INFO_FRAGMENT_CLASS,
                opcode = Opcode.IGET_OBJECT,
            ),
            fieldAccess(
                type = "I",
                opcode = Opcode.IGET,
            ),
        ),
)

object CommentButtonOnClickFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("select_comment_screen_delete_comments_tap", "comment_share_click"),
)

internal object UserTagInfoDictInitFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/api/schemas/UserTagInfoDict;",
    name = "<init>",
)

object ReelsInlineQualitySurveyRelatedFingerprint : Fingerprint(
    strings = listOf("reels_inline_quality_survey"),
    parameters = listOf(MEDIA_CLASS_NAME),
)
