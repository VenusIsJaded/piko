/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.stories.loopStory

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.p0Register
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal object StoryProgressCompletedFingerprint : Fingerprint(
    returnType = "V",
    definingClass = "Linstagram/features/stories/fragment/ReelViewerFragment;",
    strings = listOf("userSession"),
    parameters = listOf("Ljava/lang/Object;"),
)

@Suppress("unused")
val loopStoryPatch =
    bytecodePatch(
        name = "Loop story",
        description = "Replay the current story when it ends",
    ) {
        dependsOn(settingsPatch)

        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            StoryProgressCompletedFingerprint.method.apply {
                val entryCast = getInstruction(indexOfFirstInstructionOrThrow(Opcode.CHECK_CAST))
                val reelItemRegister = entryCast.registersUsed[0]
                val reelItemType = entryCast.getReference<TypeReference>()!!.type

                // 447 rebuilt the story player: Fms no longer contains the `resume`
                // string nor the old seek(I,Z)V call. Progress is now a float set via
                // LX/AE8;->A05(F)V (the only (F)V invoke in Fms, idx 195), fed by
                // LX/9w8;->DSi(ReelItem)LX/AE8; (idx 193) after iget A25 (idx 191).
                // Looping = reset progress to 0.0F and re-enter that block, mirroring
                // the old seek(0,true)+goto-restart semantics.
                // Verified against 447.0.0.55.81 (385311944).
                val fetchIndex =
                    indexOfFirstInstructionOrThrow {
                        opcode == Opcode.INVOKE_INTERFACE &&
                            getReference<MethodReference>()?.let { reference ->
                                reference.returnType == "LX/AE8;" ||
                                    (reference.returnType.endsWith("/AE8;") &&
                                        reference.parameterTypes.map(CharSequence::toString) ==
                                        listOf("Lcom/instagram/model/reels/ReelItem;"))
                            } == true
                    }

                val progressCallIndex =
                    indexOfFirstInstructionOrThrow(fetchIndex) {
                        opcode == Opcode.INVOKE_VIRTUAL &&
                            getReference<MethodReference>()?.let { reference ->
                                reference.returnType == "V" &&
                                    reference.parameterTypes.map(CharSequence::toString) ==
                                    listOf("F")
                            } == true
                    }

                val progressInstruction = getInstruction(progressCallIndex)
                val progressObjectRegister = progressInstruction.registersUsed[0]
                val progressFloatRegister = progressInstruction.registersUsed[1]

                val restartIndex =
                    indexOfFirstInstructionReversedOrThrow(
                        fetchIndex,
                        Opcode.IGET_OBJECT,
                    )

                check(
                    p0Register > 0 &&
                        reelItemRegister > 0 &&
                        progressObjectRegister < p0Register &&
                        progressFloatRegister < p0Register,
                ) {
                    "Story restart block does not keep its state in local registers"
                }

                addInstructionsWithLabels(
                    0,
                    """
                    ${PREF_CALL_DESCRIPTOR}->loopStory()Z
                    move-result v0
                    if-eqz v0, :piko
                    check-cast v$reelItemRegister, $reelItemType
                    const/4 v$progressFloatRegister, 0x0
                    goto :piko_loop
                    """.trimIndent(),
                    ExternalLabel("piko", getInstruction(0)),
                    ExternalLabel("piko_loop", getInstruction(restartIndex)),
                )
            }
            enableSettings("loopStory")
        }
    }
