package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PreparePoolTest {
    @Test void theDefaultLeavesTwoProcessorsFreeAndStopsAtSixteen() {
        assertEquals(16, PreparePool.threads(null, 32));
        assertEquals(14, PreparePool.threads("", 16));
        assertEquals(6, PreparePool.threads("  ", 8));
        assertEquals(1, PreparePool.threads(null, 2));
        assertEquals(1, PreparePool.threads(null, 1));
    }

    @Test void aConfiguredCountIsTakenAndZeroMeansSequential() {
        assertEquals(0, PreparePool.threads("0", 32));
        assertEquals(4, PreparePool.threads(" 4 ", 32));
        assertEquals(64, PreparePool.threads("999", 32));
        assertEquals(0, PreparePool.threads("-3", 32));
        assertEquals(6, PreparePool.threads("many", 8)); // unreadable: the default
    }

    @Test void warmUpsTakeAQuarterOfTheWorkers() {
        assertEquals(4, PreparePool.aheadThreads(16));
        assertEquals(1, PreparePool.aheadThreads(3));
        assertEquals(1, PreparePool.aheadThreads(1));
        assertEquals(0, PreparePool.aheadThreads(0));
    }

    @Test void resultsAreTakenInTheOrderTheyWereAskedForWhateverFinishesFirst() throws Exception {
        List<Future<Integer>> tasks = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            int value = index;
            tasks.add(PreparePool.submit(() -> {
                Thread.sleep((40 - value) % 7); // later requests finish earlier
                return value * value;
            }));
        }
        for (int index = 0; index < 40; index++) assertEquals(index * index, tasks.get(index).get());
    }

    @Test void aWarmUpRunsItsTasksAndHandsBackItsResult() throws Exception {
        int result = PreparePool.background(() -> PreparePool.submit(() -> 21).get() * 2);
        assertEquals(42, result);
    }

    @Test void theClassDigestFollowsContentNotListingOrder(@TempDir Path first, @TempDir Path second, @TempDir Path empty) throws Exception {
        Files.createDirectories(first.resolve("glsl"));
        Files.createDirectories(second.resolve("glsl"));
        Files.write(first.resolve("glsl/B.class"), new byte[]{1, 2, 3});
        Files.write(first.resolve("A.class"), new byte[]{4});
        Files.write(first.resolve("notes.txt"), new byte[]{9}); // not a class: ignored
        Files.write(second.resolve("A.class"), new byte[]{4});
        Files.write(second.resolve("glsl/B.class"), new byte[]{1, 2, 3});
        String digest = ClassTreeDigest.of(first);
        assertEquals(16, digest.length());
        assertEquals(digest, ClassTreeDigest.of(second));
        Files.write(second.resolve("glsl/B.class"), new byte[]{1, 2, 4}); // a changed translator class
        assertNotEquals(digest, ClassTreeDigest.of(second));
        Files.write(second.resolve("glsl/B.class"), new byte[]{1, 2, 3});
        Files.move(second.resolve("A.class"), second.resolve("C.class")); // a renamed one
        assertNotEquals(digest, ClassTreeDigest.of(second));
        assertNull(ClassTreeDigest.of(empty));
        assertNull(ClassTreeDigest.of(empty.resolve("missing")));
    }
}
