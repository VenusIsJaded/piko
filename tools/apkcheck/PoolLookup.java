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
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves an obfuscated "string pool" accessor of the shape
 * {@code static String A00(int key)}, whose body is one switch returning a
 * literal per key.
 *
 * <p>Recent Instagram builds move many string constants out of the method that
 * uses them and into such a pool. That silently breaks any fingerprint matching
 * on the literal, because the string is no longer present in the method at all.
 * Use this to recover the value a key maps to, or to search the pool for a value
 * and learn its key.
 *
 * <p>Usage:
 * <pre>
 *   java PoolLookup &lt;dexDir&gt; &lt;poolClass&gt; &lt;poolMethod&gt; key  &lt;n&gt;
 *   java PoolLookup &lt;dexDir&gt; &lt;poolClass&gt; &lt;poolMethod&gt; find &lt;literal&gt;
 *   java PoolLookup &lt;dexDir&gt; &lt;poolClass&gt; &lt;poolMethod&gt; dump
 * </pre>
 */
public final class PoolLookup {

    private PoolLookup() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PoolLookup <dexDir> <poolClass> <poolMethod> "
                    + "(key <n> | find <literal> | dump)");
            System.exit(2);
        }

        Method method = findMethod(args[0], args[1], args[2]);
        if (method == null) {
            System.err.println("method not found: " + args[1] + "->" + args[2]);
            System.exit(1);
            return;
        }

        MethodImplementation impl = method.getImplementation();
        if (impl == null) {
            System.err.println("method has no implementation");
            System.exit(1);
            return;
        }

        // Materialise the stream once. Switch payloads address their targets by
        // code offset, so both the instruction list and its offsets are needed.
        List<Instruction> instructions = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        int offset = 0;
        for (Instruction insn : impl.getInstructions()) {
            instructions.add(insn);
            offsets.add(offset);
            offset += insn.getCodeUnits();
        }

        SwitchPayload payload = null;
        int switchOffset = -1;
        for (int i = 0; i < instructions.size(); i++) {
            Instruction insn = instructions.get(i);
            if (insn.getOpcode() == Opcode.PACKED_SWITCH || insn.getOpcode() == Opcode.SPARSE_SWITCH) {
                switchOffset = offsets.get(i);
            }
            if (insn instanceof SwitchPayload) {
                payload = (SwitchPayload) insn;
            }
        }
        if (payload == null || switchOffset < 0) {
            System.err.println("no switch payload in " + args[1] + "->" + args[2]);
            System.exit(1);
            return;
        }

        String command = args[3];
        switch (command) {
            case "key": {
                int wanted = Integer.parseInt(args[4]);
                String value = null;
                for (SwitchElement element : payload.getSwitchElements()) {
                    if (element.getKey() == wanted) {
                        value = resolveAt(instructions, offsets, switchOffset + element.getOffset());
                        break;
                    }
                }
                System.out.println(value == null ? "(no mapping for key " + wanted + ")" : value);
                break;
            }
            case "find": {
                String needle = args[4];
                for (SwitchElement element : payload.getSwitchElements()) {
                    String value = resolveAt(instructions, offsets, switchOffset + element.getOffset());
                    if (needle.equals(value)) {
                        System.out.println(element.getKey() + "\t" + value);
                    }
                }
                break;
            }
            case "dump": {
                for (SwitchElement element : payload.getSwitchElements()) {
                    String value = resolveAt(instructions, offsets, switchOffset + element.getOffset());
                    System.out.println(element.getKey() + "\t" + (value == null ? "" : value));
                }
                break;
            }
            default:
                System.err.println("unknown command: " + command);
                System.exit(2);
        }
    }

    /** The string literal loaded at the branch target of one switch case. */
    private static String resolveAt(List<Instruction> instructions, List<Integer> offsets, int target) {
        int index = offsets.indexOf(target);
        if (index < 0) return null;

        // A case body is `const-string vX, "..."` followed by `return-object`.
        for (int i = index; i < instructions.size() && i < index + 3; i++) {
            Instruction insn = instructions.get(i);
            if (insn instanceof ReferenceInstruction) {
                Reference ref = ((ReferenceInstruction) insn).getReference();
                if (ref instanceof StringReference) {
                    return ((StringReference) ref).getString();
                }
            }
        }
        return null;
    }

    private static Method findMethod(String dexDir, String className, String methodName) throws Exception {
        File[] dexFiles = new File(dexDir).listFiles((dir, name) -> name.endsWith(".dex"));
        if (dexFiles == null) return null;
        Arrays.sort(dexFiles);

        for (File file : dexFiles) {
            DexFile dex = DexFileFactory.loadDexFile(file, Opcodes.getDefault());
            for (ClassDef classDef : dex.getClasses()) {
                if (!classDef.getType().equals(className)) continue;
                for (Method method : classDef.getMethods()) {
                    if (method.getName().equals(methodName)) return method;
                }
            }
        }
        return null;
    }
}
