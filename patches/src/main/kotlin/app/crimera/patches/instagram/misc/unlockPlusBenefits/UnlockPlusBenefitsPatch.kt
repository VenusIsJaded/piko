/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.unlockPlusBenefits

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PREF_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Class that tracks which subscription benefits are active.
 *
 * Identified by its telemetry helper, which is the only method still carrying the
 * "is_benefit_active" literal on 447: `A04(String, String)V`.
 */
internal object BenefitTelemetryClassFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("is_benefit_active", "benefit_eligibility_check_error"),
)

// The "is_benefit_active" literal no longer lives in the boolean check itself, so requiring both
// a `Z` return and that string in one method matched nothing and this fingerprint threw. On 447
// the string sits in the void telemetry helper the check delegates to.
//
// The method that actually answers "is this benefit active" reads the active-benefit set:
//
//     A0F(Ljava/lang/String;)Z            public final, 9 external callers
//         invoke-static    A02(...)V
//         iget-object      A04:Ljava/util/Set;
//         invoke-interface Ljava/util/Set;->contains(Ljava/lang/Object;)Z
//         ...
//         invoke-direct    A04(Ljava/lang/String;,Ljava/lang/String;)V   <- telemetry
//
// Resolve the class by the telemetry string, then pick that method. The `Set.contains` probe is
// what separates it from the private helper `A07(String)Z`, which only compares timestamps and
// is called solely by `A0F`.
internal object ActiveBenefitCheckerFingerprint : Fingerprint(
    classFingerprint = BenefitTelemetryClassFingerprint,
    parameters = listOf("Ljava/lang/String;"),
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters =
        listOf(
            methodCall(
                definingClass = "Ljava/util/Set;",
                name = "contains",
                opcode = Opcode.INVOKE_INTERFACE,
            ),
        ),
)

@Suppress("unused")
val unlockPlusBenefitsPatch =
    bytecodePatch(
        name = "Unlock Plus benefits",
        description = "Unlocks 'Plus' subscription benefits that are checked locally. USE IT AT YOUR OWN RISK",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)
        execute {

            ActiveBenefitCheckerFingerprint.method.apply {

                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, $PREF_DESCRIPTOR->unlockPlusBenefits()Z
                    move-result v0
                    if-eqz v0, :piko_continue
                    return v0
                    """.trimIndent(),
                    ExternalLabel("piko_continue", getInstruction(0)),
                )

                enableSettings("unlockPlusBenefits")
            }
        }
    }
