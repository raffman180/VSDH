package de.stranger.vsdh.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read active interfaces from optimized SPIR-V. */
public final class SpirvInterface {
    private SpirvInterface() {}

    static final int INPUT = 1;
    static final int OUTPUT = 3;
    private static final int HEADER_BYTES = 20;
    private static final int OP_NAME = 5;
    private static final int OP_VARIABLE = 59;
    private static final int OP_DECORATE = 71;
    private static final int LOCATION = 30;
    private static final Pattern INPUT_LAYOUT = Pattern.compile(
            "\\blayout\\s*\\(([^)]*)\\)\\s*(?:(?:flat|smooth|noperspective|centroid|sample|patch|invariant)\\s+)*"
                    + "in\\s+(?:(?:lowp|mediump|highp)\\s+)?\\w+\\s+([^;{}]+);");
    private static final Pattern LOCATION_KEY = Pattern.compile("\\blocation\\s*=");
    private static final Pattern IDENTIFIER = Pattern.compile("\\s*(\\w+)");

    /** Legacy GLSL matches implicit varyings by name. Vulkan matches numeric locations. */
    static void linkGraphics(byte[] vertex, byte[] fragment, String fragmentSource) {
        link(vertex, fragment, fragmentSource);
    }

    /** The prefix a geometry stage's colliding outputs take; the fragment stage reads the bare name. */
    static final String GEOMETRY_OUTPUT = "ofGsOut_";
    private static final String QUALIFIERS = "(?:(?:flat|smooth|noperspective|centroid|sample|invariant|highp|mediump|lowp)\\s+)*";
    private static final Pattern GEOMETRY_INPUT_DECLARATION = Pattern.compile(
            "\\b" + QUALIFIERS + "in\\s+" + QUALIFIERS + "\\w+\\s+(\\w+)\\s*\\[");
    private static final Pattern GEOMETRY_OUTPUT_DECLARATION = Pattern.compile(
            "\\b" + QUALIFIERS + "out\\s+" + QUALIFIERS + "\\w+\\s+(\\w+)\\s*;");

    /**
     * A geometry (or tessellation evaluation) stage reads its corners as arrays and writes plain
     * outputs. Flattened blocks give both the same name; every output whose name an input array also
     * has is renamed, at each use that is neither an array access nor a member access.
     */
    static String renameGeometryOutputs(String source) {
        Set<String> inputs = new HashSet<>();
        var in = GEOMETRY_INPUT_DECLARATION.matcher(source);
        while (in.find()) inputs.add(in.group(1));
        Set<String> clashing = new HashSet<>();
        var out = GEOMETRY_OUTPUT_DECLARATION.matcher(source);
        while (out.find()) if (inputs.contains(out.group(1))) clashing.add(out.group(1));
        for (String name : clashing)
            source = source.replaceAll("(?<![.\\w])" + Pattern.quote(name) + "\\b(?!\\s*\\[)", GEOMETRY_OUTPUT + name);
        return source;
    }

    /** The next stage's inputs take the locations of the previous stage's outputs of the same unprefixed name. */
    static void linkStages(byte[] previous, byte[] next, String nextSource) {
        link(previous, next, nextSource);
    }

    private static String unprefixed(String name) {
        if (name.startsWith(GEOMETRY_OUTPUT)) return name.substring(GEOMETRY_OUTPUT.length());
        if (name.startsWith(dev.vitrail.glsl.GlslTranslator.TESSELLATION_OUTPUT))
            return "of_" + name.substring(dev.vitrail.glsl.GlslTranslator.TESSELLATION_OUTPUT.length());
        return name;
    }

    /**
     * Patches every input of {@code next} that the previous stage writes under the same (unprefixed)
     * name to that output's location. An input nobody writes keeps the compiler's own location, which
     * may now coincide with a patched one; such inputs are moved past every used location, because
     * two inputs on one location are undefined behaviour rather than a compile error.
     */
    private static void link(byte[] previous, byte[] next, String nextSource) {
        Set<String> explicit = new HashSet<>();
        var declaration = INPUT_LAYOUT.matcher(nextSource);
        while (declaration.find()) {
            if (!LOCATION_KEY.matcher(declaration.group(1)).find()) continue;
            for (String variable : declaration.group(2).split(",")) {
                var identifier = IDENTIFIER.matcher(variable);
                if (identifier.lookingAt()) explicit.add(identifier.group(1));
            }
        }
        Map<String, StageLocation> outputs = new HashMap<>();
        stageLocations(previous, OUTPUT).forEach((name, location) -> outputs.put(unprefixed(name), location));
        var words = ByteBuffer.wrap(next).order(ByteOrder.LITTLE_ENDIAN);
        var inputs = stageLocations(next, INPUT);
        Set<Integer> taken = new HashSet<>();
        Set<String> settled = new HashSet<>();
        inputs.forEach((name, input) -> {
            var output = outputs.get(name);
            if (explicit.contains(name)) { taken.add(input.value()); settled.add(name); }
            else if (output != null) { words.putInt(input.offset(), output.value()); taken.add(output.value()); settled.add(name); }
        });
        int free = taken.stream().mapToInt(Integer::intValue).max().orElse(-1) + 4;
        for (var entry : inputs.entrySet()) {
            if (settled.contains(entry.getKey()) || !taken.contains(entry.getValue().value())) continue;
            words.putInt(entry.getValue().offset(), free);
            taken.add(free);
            free += 4;
        }
    }

    record StageLocation(int offset, int value) {}

    static Map<String, StageLocation> stageLocations(byte[] spirv, int storageClass) {
        bindings(spirv); // validate the instruction stream before walking it
        ByteBuffer words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        Map<Integer, String> names = new HashMap<>();
        Map<Integer, StageLocation> locations = new HashMap<>();
        Set<Integer> variables = new HashSet<>();
        for (int cursor = HEADER_BYTES; cursor < words.limit();) {
            int instruction = words.getInt(cursor);
            int count = instruction >>> 16;
            int opcode = instruction & 0xffff;
            int next = cursor + count * 4;
            if (opcode == OP_NAME && count >= 3) {
                int start = cursor + 8;
                int end = start;
                while (end < next && spirv[end] != 0) end++;
                names.put(words.getInt(cursor + 4), new String(spirv, start, end - start, StandardCharsets.UTF_8));
            }
            if (opcode == OP_DECORATE && count >= 4 && words.getInt(cursor + 8) == LOCATION) {
                int offset = cursor + 12;
                locations.put(words.getInt(cursor + 4), new StageLocation(offset, words.getInt(offset)));
            }
            if (opcode == OP_VARIABLE && count >= 4 && words.getInt(cursor + 12) == storageClass)
                variables.add(words.getInt(cursor + 8));
            cursor = next;
        }
        Map<String, StageLocation> result = new HashMap<>();
        for (int id : variables) {
            if (names.containsKey(id) && locations.containsKey(id)) result.put(names.get(id), locations.get(id));
        }
        return result;
    }

    /** shaderc's debug flag retains names, but the expanded GLSL is already stored separately. */
    static byte[] withoutDebugSource(byte[] spirv) {
        ByteBuffer words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        byte[] result = new byte[spirv.length];
        System.arraycopy(spirv, 0, result, 0, HEADER_BYTES);
        int written = HEADER_BYTES;
        for (int cursor = HEADER_BYTES; cursor < spirv.length;) {
            int instruction = words.getInt(cursor);
            int bytes = (instruction >>> 16) * 4;
            int opcode = instruction & 0xffff;
            if (opcode != 2 && opcode != 3) { // OpSourceContinued and OpSource
                System.arraycopy(spirv, cursor, result, written, bytes);
                written += bytes;
            }
            cursor += bytes;
        }
        return Arrays.copyOf(result, written);
    }

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
