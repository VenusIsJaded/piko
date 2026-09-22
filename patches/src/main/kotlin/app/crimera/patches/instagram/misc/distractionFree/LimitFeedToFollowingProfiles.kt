/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.distractionFree

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$PATCHES_DESCRIPTOR/feed/LimitFeedToFollowingProfiles;"

// Two classes ship a `toString` with both of these literals on 447 -- `LX/2vc;` and `LX/4Ve;` --
// so this fingerprint was ambiguous and Morphe kept whichever it walked first. Only `LX/2vc;` is
// the main-feed request: it is the one carrying `Ljava/util/Map;` header fields (A0M/A0N/A0O) that
// this patch goes on to look up, and the one `MainFeedHeaderMapFinderFingerprint`'s method
// actually reads (`iget-object LX/2vc;->A0M:Ljava/util/Map;`). `LX/4Ve;` has no `Map` field at
// all, so binding to it made the later header-field lookup throw.
//
// Requiring the declaring class to actually have a `Map` field picks the right one. (The field is
// not read inside `toString` itself, so this has to be a class predicate rather than a filter.)
private object MainFeedRequestClassFingerprint : Fingerprint(
    strings = listOf("Request{mReason=", ", mInstanceNumber="),
    custom = { _, classDef ->
        classDef.fields.any { it.type == "Ljava/util/Map;" }
    },
)

private object InitMainFeedRequestFingerprint : Fingerprint(
    name = "<init>",
    classFingerprint = MainFeedRequestClassFingerprint,
)

private object MainFeedHeaderMapFinderFingerprint : Fingerprint(
    strings = listOf("pagination_source", "FEED_REQUEST_SENT"),
)

@Suppress("unused")
val limitFeedToFollowingProfiles =
    bytecodePatch(
        name = "Limit feed to following profiles",
        description = "Filters the home feed to display only content from profiles you follow.",
    ) {
        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            /**
             * Since the header field is obfuscated and there is no easy way to identify it among all the class fields,
             * an additional method is fingerprinted.
             * This method uses the map, so we can get the field name of the map field using this.
             */
            val mainFeedRequestHeaderFieldName: String

            with(MainFeedHeaderMapFinderFingerprint.method) {
                mainFeedRequestHeaderFieldName =
                    indexOfFirstInstructionOrThrow {
                        getReference<FieldReference>().let { ref ->
                            ref?.type == "Ljava/util/Map;" &&
                                ref.definingClass == MainFeedRequestClassFingerprint.classDef.toString()
                        }
                    }.let { instructionIndex ->
                        getInstruction(instructionIndex).getReference<FieldReference>()!!.name
                    }
            }

            InitMainFeedRequestFingerprint.method.apply {
                // Finds the instruction where the map is being initialized in the constructor
                val getHeaderIndex =
                    indexOfFirstInstructionOrThrow {
                        getReference<FieldReference>().let {
                            it?.name == mainFeedRequestHeaderFieldName
                        }
                    }

                val paramHeaderRegister = getInstruction<TwoRegisterInstruction>(getHeaderIndex).registerA

                addInstructions(
                    getHeaderIndex,
                    """
                    invoke-static { v$paramHeaderRegister }, $EXTENSION_CLASS_DESCRIPTOR->setFollowingHeader(Ljava/util/Map;)Ljava/util/Map;
                    move-result-object v$paramHeaderRegister
                """,
                )
            }

            enableSettings("limitFollowingFeed")
        }
    }
