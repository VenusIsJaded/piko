/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Flattens every {@code classes*.dex} of a target APK into one tab separated
 * index, so fingerprints can be resolved without holding all dex files in
 * memory at once.
 *
 * <p>Record types (first column):
 * <pre>
 *   C  type  superclass  interfaces,csv  accessFlags  dexFileName
 *   F  classType  fieldName  fieldType  accessFlags
 *   M  classType  methodName  params,csv  returnType  accessFlags  insnCount
 *   S  classType  methodName  params,csv  insnIndex  stringLiteral
 *   R  classType  methodName  params,csv  insnIndex  opcode  kind  ...ref
 *   L  classType  methodName  params,csv  insnIndex  opcode  literal
 *   O  classType  methodName  params,csv  insnIndex  opcode
 * </pre>
 *
 * <p>Usage: {@code java Idx <dexDir> <outputTsv>}
 */
public final class Idx {

    private Idx() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Idx <dexDir> <outputTsv>");
            System.exit(2);
        }

        File[] dexFiles = new File(args[0]).listFiles((dir, name) -> name.endsWith(".dex"));
        if (dexFiles == null || dexFiles.length == 0) {
            System.err.println("no .dex files under " + args[0]);
            System.exit(2);
            return;
        }
        Arrays.sort(dexFiles);

        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(args[1]), 1 << 20))) {
            for (File dexFile : dexFiles) {
                DexFile dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault());
                for (ClassDef classDef : dex.getClasses()) {
                    writeClass(out, classDef, dexFile.getName());
                }
                System.err.println("indexed " + dexFile.getName());
            }
        }
    }

    private static void writeClass(PrintWriter out, ClassDef classDef, String dexName) {
        StringBuilder interfaces = new StringBuilder();
        for (String iface : classDef.getInterfaces()) {
            if (interfaces.length() > 0) interfaces.append(',');
            interfaces.append(iface);
        }
        out.println("C\t" + classDef.getType() + '\t' + classDef.getSuperclass() + '\t'
                + interfaces + '\t' + classDef.getAccessFlags() + '\t' + dexName);

        for (Field field : classDef.getFields()) {
            out.println("F\t" + classDef.getType() + '\t' + field.getName() + '\t'
                    + field.getType() + '\t' + field.getAccessFlags());
        }

        for (Method method : classDef.getMethods()) {
            writeMethod(out, classDef, method);
        }
    }

    private static void writeMethod(PrintWriter out, ClassDef classDef, Method method) {
        StringBuilder params = new StringBuilder();
        for (CharSequence param : method.getParameterTypes()) {
            if (params.length() > 0) params.append(',');
            params.append(param);
        }
        String key = classDef.getType() + '\t' + method.getName() + '\t' + params;

        MethodImplementation impl = method.getImplementation();
        int insnCount = 0;
        if (impl != null) {
            for (Instruction ignored : impl.getInstructions()) insnCount++;
        }
        out.println("M\t" + key + '\t' + method.getReturnType() + '\t'
                + method.getAccessFlags() + '\t' + insnCount);

        if (impl == null) return;

        int index = 0;
        for (Instruction insn : impl.getInstructions()) {
            writeInstruction(out, key, index++, insn);
        }
    }

    private static void writeInstruction(PrintWriter out, String key, int index, Instruction insn) {
        Opcode opcode = insn.getOpcode();

        if (insn instanceof ReferenceInstruction) {
            Reference ref = null;
            try {
                ref = ((ReferenceInstruction) insn).getReference();
            } catch (Exception ignored) {
                // Malformed reference; fall through and record the opcode only.
            }

            if (ref instanceof StringReference) {
                out.println("S\t" + key + '\t' + index + '\t' + escape(((StringReference) ref).getString()));
                return;
            }
            if (ref instanceof FieldReference) {
                FieldReference field = (FieldReference) ref;
                out.println("R\t" + key + '\t' + index + '\t' + opcode.name + "\tF\t"
                        + field.getDefiningClass() + '\t' + field.getName() + '\t' + field.getType());
                return;
            }
            if (ref instanceof MethodReference) {
                MethodReference target = (MethodReference) ref;
                StringBuilder params = new StringBuilder();
                for (CharSequence param : target.getParameterTypes()) {
                    if (params.length() > 0) params.append(',');
                    params.append(param);
                }
                out.println("R\t" + key + '\t' + index + '\t' + opcode.name + "\tM\t"
                        + target.getDefiningClass() + '\t' + target.getName() + '\t'
                        + params + '\t' + target.getReturnType());
                return;
            }
            if (ref instanceof TypeReference) {
                out.println("R\t" + key + '\t' + index + '\t' + opcode.name + "\tT\t"
                        + ((TypeReference) ref).getType());
                return;
            }
            out.println("R\t" + key + '\t' + index + '\t' + opcode.name + "\t?");
            return;
        }

        if (insn instanceof WideLiteralInstruction) {
            out.println("L\t" + key + '\t' + index + '\t' + opcode.name + '\t'
                    + ((WideLiteralInstruction) insn).getWideLiteral());
            return;
        }

        out.println("O\t" + key + '\t' + index + '\t' + opcode.name);
    }

    /** Keeps one record on one line so the TSV stays parseable. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
