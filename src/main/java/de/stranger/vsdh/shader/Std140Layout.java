package de.stranger.vsdh.shader;

import dev.vitrail.glsl.TranslatedUnit.Uniform;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Layout shared by shader translation and native UBO upload; all offsets are bytes. */
public final class Std140Layout {
    private static final Pattern ARRAY = Pattern.compile("\\[\\s*(\\d+)\\s*]");
    private static final Pattern MATRIX = Pattern.compile("(d?)mat([2-4])(?:x([2-4]))?");
    // Precompiled: the write path runs for every active uniform in every frame.
    private static final Pattern FLOAT_VECTOR = Pattern.compile("vec[2-4]|mat[2-4](?:x[2-4])?");
    private static final Pattern INTEGER = Pattern.compile("int|uint|bool|[iub]vec[2-4]");
    private static final int MAX_BYTES = 1 << 20;
    public record Member(String name, String type, int offset, int size, int alignment,
                         int arrayLength, int arrayStride, int matrixStride) {}
    private final List<Member> members;
    /** A member with its parsed type: one lookup per write instead of a second one in the type table. */
    private record Slot(Member member, TypeInfo info) {}
    private final Map<String, Slot> names;
    private final int size;

    public Std140Layout(List<Uniform> uniforms) {
        var ordered = new ArrayList<Member>();
        var indexed = new LinkedHashMap<String, Member>();
        int cursor = 0, blockAlignment = 16;
        for (Uniform uniform : uniforms) {
            Shape shape = shape(uniform.type());
            int arrayLength = 0;
            var arrays = ARRAY.matcher(uniform.declaration());
            if (arrays.find()) {
                arrayLength = Integer.parseInt(arrays.group(1));
                if (arrayLength < 1 || arrays.find())
                    throw new IllegalArgumentException("Unsupported uniform array: " + uniform.declaration());
            }
            // Reject symbolic/unsized dimensions rather than packing a scalar in their place.
            if (uniform.declaration().contains("[") && arrayLength == 0)
                throw new IllegalArgumentException("Unresolved uniform array: " + uniform.declaration());
            int alignment = arrayLength == 0 ? shape.alignment : align(shape.alignment, 16);
            int arrayStride = arrayLength == 0 ? 0 : align(shape.size, alignment);
            int bytes = arrayLength == 0 ? shape.size : Math.multiplyExact(arrayLength, arrayStride);
            cursor = align(cursor, alignment);
            var member = new Member(uniform.name(), uniform.type(), cursor, bytes, alignment,
                    arrayLength, arrayStride, shape.matrixStride);
            if (indexed.putIfAbsent(member.name(), member) != null)
                throw new IllegalArgumentException("Duplicate uniform " + member.name());
            ordered.add(member);
            cursor = Math.addExact(cursor, bytes);
            if (cursor > MAX_BYTES) throw new IllegalArgumentException("Uniform block exceeds " + MAX_BYTES + " bytes");
            blockAlignment = Math.max(blockAlignment, alignment);
        }
        members = List.copyOf(ordered);
        var slots = new LinkedHashMap<String, Slot>();
        indexed.forEach((name, member) -> slots.put(name, new Slot(member, typeInfo(member.type()))));
        names = Map.copyOf(slots);
        size = align(cursor, blockAlignment);
    }

    public List<Member> members() { return members; }
    public int size() { return size; }
    public Member member(String name) { return slot(name).member; }
    private Slot slot(String name) {
        Slot slot = names.get(name);
        if (slot == null) throw new IllegalArgumentException("Unknown uniform " + name);
        return slot;
    }

    /** Parsed once per GLSL type: the write path runs for every active uniform in every frame. */
    private record TypeInfo(boolean floats, boolean ints, int columns, int rows) {}
    private static final Map<String, TypeInfo> TYPES = new java.util.concurrent.ConcurrentHashMap<>();
    private static TypeInfo typeInfo(String type) { return TYPES.computeIfAbsent(type, Std140Layout::parseType); }
    private static TypeInfo parseType(String type) {
        boolean floats = type.equals("float") || FLOAT_VECTOR.matcher(type).matches();
        boolean ints = INTEGER.matcher(type).matches();
        var matrix = MATRIX.matcher(type);
        if (matrix.matches()) {
            int columns = Integer.parseInt(matrix.group(2));
            int rows = matrix.group(3) == null ? columns : Integer.parseInt(matrix.group(3));
            return new TypeInfo(floats, ints, columns, rows);
        }
        return new TypeInfo(floats, ints, 0, 0);
    }

    /** Write scalar/vector/matrix float values in column-major order without disturbing padding. */
    public void putFloats(ByteBuffer destination, String name, int arrayIndex, float... values) {
        Slot slot = slot(name);
        Member member = slot.member;
        TypeInfo info = slot.info;
        if (!info.floats())
            throw new IllegalArgumentException("Not a float uniform: " + member.type);
        int offset = elementOffset(destination, member, arrayIndex);
        if (info.columns() > 0) {
            int columns = info.columns();
            int rows = info.rows();
            if (values.length != columns * rows) throw new IllegalArgumentException("Wrong matrix value count: " + name);
            for (int column = 0; column < columns; column++)
                for (int row = 0; row < rows; row++)
                    destination.putFloat(offset + column * member.matrixStride + row * 4, values[column * rows + row]);
        } else {
            int count = member.type.equals("float") ? 1 : member.type.charAt(member.type.length() - 1) - '0';
            if (values.length != count) throw new IllegalArgumentException("Wrong vector value count: " + name);
            for (int i = 0; i < count; i++) destination.putFloat(offset + i * 4, values[i]);
        }
    }

    public void putInts(ByteBuffer destination, String name, int arrayIndex, int... values) {
        Slot slot = slot(name);
        Member member = slot.member;
        if (!slot.info.ints())
            throw new IllegalArgumentException("Not an integer uniform: " + member.type);
        int offset = elementOffset(destination, member, arrayIndex);
        int count = member.type.length() <= 4 ? 1 : member.type.charAt(member.type.length() - 1) - '0';
        if (values.length != count) throw new IllegalArgumentException("Wrong integer value count: " + name);
        for (int i = 0; i < count; i++) destination.putInt(offset + i * 4, values[i]);
    }

    private int elementOffset(ByteBuffer destination, Member member, int index) {
        if (destination.capacity() < size) throw new IllegalArgumentException("Uniform destination too small");
        if (index < 0 || index >= Math.max(1, member.arrayLength)) throw new IndexOutOfBoundsException(index);
        return member.offset + member.arrayStride * index;
    }

    private record Shape(int alignment, int size, int matrixStride) {}
    private static Shape shape(String type) {
        var matrix = MATRIX.matcher(type);
        if (matrix.matches()) {
            int scalar = matrix.group(1).isEmpty() ? 4 : 8;
            int columns = Integer.parseInt(matrix.group(2));
            int rows = matrix.group(3) == null ? columns : Integer.parseInt(matrix.group(3));
            int alignment = align((rows == 2 ? 2 : 4) * scalar, 16);
            int stride = align(rows * scalar, alignment);
            return new Shape(alignment, columns * stride, stride);
        }
        if (type.equals("OfFog")) return new Shape(16, 32, 0);
        if (type.matches("float|int|uint|bool")) return new Shape(4, 4, 0);
        if (type.equals("double")) return new Shape(8, 8, 0);
        if (type.matches("[diub]?vec[2-4]")) {
            int scalar = type.startsWith("d") ? 8 : 4;
            int components = type.charAt(type.length() - 1) - '0';
            return new Shape((components == 2 ? 2 : 4) * scalar, components * scalar, 0);
        }
        throw new IllegalArgumentException("Unsupported std140 type: " + type);
    }

    private static int align(int value, int alignment) {
        return Math.multiplyExact(Math.floorDiv(Math.addExact(value, alignment - 1), alignment), alignment);
    }
}
