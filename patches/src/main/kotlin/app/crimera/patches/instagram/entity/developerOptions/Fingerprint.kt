/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.entity.developerOptions

import app.crimera.patches.instagram.utils.Constants.ENTITY_CLASS
import app.morphe.patcher.Fingerprint

internal const val EXTENSION_CLASS_DESCRIPTOR = "$ENTITY_CLASS/DeveloperOptions;"
internal const val ITEM_CLASS_DESCRIPTOR = "$ENTITY_CLASS/DeveloperOptionsItem;"

internal object GetUniversalIdHelperClassExtension : Fingerprint(
    name = "getUniversalIdHelperClass",
    definingClass = ITEM_CLASS_DESCRIPTOR,
)

internal object GetQuickExperimentHelperClassExtension : Fingerprint(
    name = "getQuickExperimentHelperClass",
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
)

internal object GetExperimentItemHelperClassExtension : Fingerprint(
    name = "getExperimentItemHelperClass",
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
)

internal object GetAllExperimentsClassExtension : Fingerprint(
    name = "getAllExperiments",
    definingClass = EXTENSION_CLASS_DESCRIPTOR,
)

internal object ExperimentsValueBuilderFingerprint : Fingerprint(
    strings = listOf("default[after mc dispose]", "default[before mc init]", "override", "server"),
)

// On 447 the error message moved into the obfuscated string pool
// (`const/16 1043; invoke-static LX/000;->A00(I)Ljava/lang/String;`), so it is no longer a literal
// in this method and requiring it matched nothing. Only "ExperimentParameter" is still inline:
//
//     LX/4Xx;->A02()I
//         iget-wide     LX/4Xx;->A00:J
//         invoke-static LX/3jC;->A00(J)I      <- the universal-id helper the patch extracts
//         ...
//         const-string  "ExperimentParameter"
//         const/16      1043                  <- "Failed to get config key with specifier:%d"
//         invoke-static LX/000;->A00(I)Ljava/lang/String;
//
// Since the pool key is a build-specific index, drop the pooled string rather than pin the key.
// "ExperimentParameter" alone is not enough -- Morphe compares method strings with `contains`, so
// it also matches seven `<clinit>` methods holding `...LandingExperimentParameter;` signatures.
// Pinning the shape of the getter (`()I`) excludes all of them and leaves exactly one match.
internal object ExperimentsGetMobileConfigSpecifier : Fingerprint(
    strings = listOf("ExperimentParameter"),
    parameters = listOf(),
    returnType = "I",
)
