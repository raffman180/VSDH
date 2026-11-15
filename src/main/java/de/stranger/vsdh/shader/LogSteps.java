package de.stranger.vsdh.shader;

/** Which repetitions of a recurring event are worth a log line: the 1st, 10th, 100th, 1000th ... */
final class LogSteps {
    private LogSteps() {}

    /** True for 1, 10, 100, ...; no allocation, a handful of divisions. */
    static boolean powerOfTen(long count) {
        if (count < 1) return false;
        while (count >= 10 && count % 10 == 0) count /= 10;
        return count == 1;
    }
}
