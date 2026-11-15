package de.stranger.vsdh.shader;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Where the time of preparing a pack goes, by named phase. Phases nest (a plan phase contains the expansion,
 * translation and compilation of its programs), so the figures are read per group and never summed across groups.
 * With parallel compilation the leaf phases add up worker time, which is then larger than the wall time around them.
 * Used on preparation workers only; nothing on the render thread calls it.
 */
public final class PrepareClock {
    private static final ConcurrentHashMap<String, LongAdder> NANOS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    private PrepareClock() {}

    public static void reset() {
        NANOS.clear();
        COUNTS.clear();
    }

    public static long start() { return System.nanoTime(); }

    /** Adds the span since {@code started} to {@code phase}. */
    public static void add(String phase, long started) {
        NANOS.computeIfAbsent(phase, key -> new LongAdder()).add(System.nanoTime() - started);
        COUNTS.computeIfAbsent(phase, key -> new LongAdder()).increment();
    }

    /** Counts an event that has no span of its own (a cache hit). */
    public static void count(String phase) {
        COUNTS.computeIfAbsent(phase, key -> new LongAdder()).increment();
    }

    public static long millis(String phase) {
        var nanos = NANOS.get(phase);
        return nanos == null ? 0 : nanos.sum() / 1_000_000;
    }

    /** Phase to "milliseconds (count)", sorted by name. */
    public static Map<String, String> snapshot() {
        var sorted = new TreeMap<String, String>();
        for (var entry : COUNTS.entrySet())
            sorted.put(entry.getKey(), millis(entry.getKey()) + " ms (" + entry.getValue().sum() + ")");
        return sorted;
    }

    public static String report() {
        var text = new StringBuilder();
        snapshot().forEach((phase, value) -> text.append(text.isEmpty() ? "" : ", ").append(phase).append(' ').append(value));
        return text.toString();
    }
}
