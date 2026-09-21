/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.entity.profileinfo

import app.crimera.patches.instagram.entity.decoder.USER_MODEL_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.utils.Constants.USER_DETAIL_VIEW_MODEL_CLASS
import app.crimera.utils.changeFirstString
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val profileInfoEntity =
    bytecodePatch(
        description = "Used to decode profile info",
    ) {
        // Provides USER_MODEL_CLASS_NAME, the obfuscation-stable user model type.
        dependsOn(decoderEntity)

        execute {

            ProfileUserInfoViewBinderFingerprint.method.apply {
                mutableClassDefBy(parameters[1].type).apply {
                    val profileRelatedDetailsClass = ProfileRelatedDetailsFingerprint.classDef

                    val profileRelatedDetailsFieldName = fields.last { it.type == profileRelatedDetailsClass.type }.name
                    GetProfileRelatedDetailsExtensionFingerprint.changeFirstString(profileRelatedDetailsFieldName)

                    val userDetailViewModelFieldName =
                        fields
                            .last { it.type == USER_DETAIL_VIEW_MODEL_CLASS }
                            .name
                    GetUserDetailViewModelExtensionFingerprint.changeFirstString(userDetailViewModelFieldName)

                    // Resolve the `User` field that `UserDetailViewModel` holds.
                    //
                    // This used to be read out of the username getter by taking the first
                    // `iget-object` at-or-after the "INVALID_USER_NAME" string. That broke in
                    // Instagram 447: the getter now reads the username through a LiveTree
                    // accessor, and the string survives only in the trailing fallback branch, so
                    // no `iget-object` follows it. `indexOfFirstInstruction` returned -1 and
                    // `getInstruction(-1)` threw IndexOutOfBoundsException.
                    //
                    // Read the field off the class definition instead. That does not depend on
                    // the shape of any single method body, so it survives this kind of churn.
                    val userDetailViewModelClass =
                        classDefByOrNull(USER_DETAIL_VIEW_MODEL_CLASS)
                            ?: throw PatchException("Could not find $USER_DETAIL_VIEW_MODEL_CLASS")

                    val userFields = userDetailViewModelClass.fields.filter { it.type == USER_MODEL_CLASS_NAME }

                    if (userFields.isEmpty()) {
                        throw PatchException(
                            "Expected a $USER_MODEL_CLASS_NAME field on $USER_DETAIL_VIEW_MODEL_CLASS, but found none",
                        )
                    }

                    val userObjectFieldName =
                        userFields.singleOrNull()?.name
                            // More than one candidate: fall back to whichever field the username
                            // getter actually reads.
                            ?: GetUsernameFromUserDetailViewModelFingerprint.method.instructions
                                .asSequence()
                                .filter { it.opcode == Opcode.IGET_OBJECT }
                                .mapNotNull { it.getReference<FieldReference>() }
                                .firstOrNull { reference ->
                                    reference.definingClass == USER_DETAIL_VIEW_MODEL_CLASS &&
                                        userFields.any { it.name == reference.name }
                                }?.name
                            ?: throw PatchException(
                                "Found ${userFields.size} $USER_MODEL_CLASS_NAME fields on " +
                                    "$USER_DETAIL_VIEW_MODEL_CLASS and could not determine which " +
                                    "one holds the profile user",
                            )

                    GetUserDataExtensionFingerprint.changeFirstString(userObjectFieldName)

                    val isSelfProfileFieldName =
                        profileRelatedDetailsClass.fields
                            .last { it.type == "Z" }
                            .name
                    IsSelfProfileExtensionFingerprint.changeFirstString(isSelfProfileFieldName)
                }
            }
        }
    }
