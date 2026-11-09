package de.stranger.vsdh.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Read descriptor bindings from optimized SPIR-V instead of guessing which GLSL declarations survived. */
public final class SpirvInterface {
    private SpirvInterface() {}

    /** Active vertex input locations. Names may be stripped by production optimisation. */
    public static Set<Integer> inputLocations(byte[] spirv) {
        bindings(spirv); // validate the instruction stream before walking it
        ByteBuffer words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        Map<Integer, Integer> locations = new HashMap<>();
        Set<Integer> inputs = new HashSet<>();
        for (int cursor = 20; cursor < words.limit();) {
            int instruction = words.getInt(cursor), count = instruction >>> 16, opcode = instruction & 65535;
            if (opcode == 71 && count >= 4 && words.getInt(cursor + 8) == 30)
                locations.put(words.getInt(cursor + 4), words.getInt(cursor + 12));
            if (opcode == 59 && count >= 4 && words.getInt(cursor + 12) == 1) inputs.add(words.getInt(cursor + 8));
            cursor += count * 4;
        }
        Set<Integer> result = new HashSet<>();
        for (int id : inputs) if (locations.containsKey(id)) result.add(locations.get(id));
        return Set.copyOf(result);
    }

    public static Set<Integer> bindings(byte[] spirv) {
        if (spirv.length < 20 || spirv.length % 4 != 0) throw new IllegalArgumentException("Invalid SPIR-V length");
        ByteBuffer words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        if (words.getInt(0) != 0x07230203) throw new IllegalArgumentException("Invalid SPIR-V header");
        Set<Integer> bindings = new HashSet<>();
        for (int cursor = 20; cursor < words.limit();) {
            int instruction = words.getInt(cursor);
            int count = instruction >>> 16;
            if (count == 0 || (long) cursor + count * 4L > words.limit())
                throw new IllegalArgumentException("Invalid SPIR-V instruction at " + cursor);
            if ((instruction & 0xffff) == 71 && count >= 4 && words.getInt(cursor + 8) == 33)
                bindings.add(words.getInt(cursor + 12)); // OpDecorate Binding
            cursor += count * 4;
        }
        return Set.copyOf(bindings);
    }
}
