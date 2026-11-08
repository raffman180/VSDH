package de.stranger.vsdh.compat;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SqliteRegenCancellationTest {
    @Test void nativeDhSqliteQueryIsCancelledBeforeConnectionCloses() throws Exception {
        // In-memory DB only; this uses DH 3.3.3's actual relocated SQLite driver.
        try (var connection = new dh_sqlite.JDBC().connect(dh_sqlite.JDBC.PREFIX + ":memory:", new Properties());
             var statement = connection.prepareStatement("WITH RECURSIVE cnt(x) AS (VALUES(0) UNION ALL "
                     + "SELECT x+1 FROM cnt WHERE x<1000000000) SELECT sum(x) FROM cnt")) {
            var control = new RegenQueryControl();
            var entered = new CountDownLatch(1);
            var cancelled = new AtomicBoolean();
            var executor = Executors.newSingleThreadExecutor();
            var future = executor.submit(() -> {
                try (var scope = control.enter()) {
                    control.executing(statement);
                    entered.countDown();
                    try (var result = statement.executeQuery()) {
                        fail("The deliberately long query finished without cancellation");
                    } catch (SQLException failure) {
                        if (!control.isOurCancellation(failure)) throw new RuntimeException(failure);
                        cancelled.set(true);
                    }
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                executor.shutdownNow();
                control.cancel();
                ExecutorShutdown.awaitStopped(executor, Duration.ofSeconds(2), control::cancel);
                future.get(1, TimeUnit.SECONDS);
                assertTrue(cancelled.get());
                assertTrue(executor.isTerminated());
                assertFalse(connection.isClosed());
            } finally {
                control.cancel();
                executor.shutdownNow();
            }
        }
    }

    @Test void unrelatedSqlErrorsAreNeverTreatedAsShutdownCancellation() {
        var control = new RegenQueryControl();
        assertFalse(control.isOurCancellation(new SQLException("interrupted", "", 9)));
        control.cancel();
        assertFalse(control.isOurCancellation(new SQLException("corrupt", "", 11)));
        assertFalse(control.isOurCancellation(new SQLException("closed", "", 21)));
        assertTrue(control.isOurCancellation(new RuntimeException(new SQLException("interrupted", "", 9))));
    }
}
