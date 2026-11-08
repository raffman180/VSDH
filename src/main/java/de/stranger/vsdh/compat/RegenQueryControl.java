package de.stranger.vsdh.compat;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

/** Scoped to DH's read-only regeneration-position query, never arbitrary saves or database writes. */
public final class RegenQueryControl {
    private static final ThreadLocal<RegenQueryControl> CURRENT = new ThreadLocal<>();
    private final AtomicReference<Statement> active = new AtomicReference<>();
    private volatile boolean stopping;

    public interface Owner { RegenQueryControl vsdh$regenQueryControl(); }
    public static RegenQueryControl current() { return CURRENT.get(); }
    public boolean stopping() { return stopping; }
    public Scope enter() {
        var previous = CURRENT.get();
        CURRENT.set(this);
        return new Scope(previous);
    }
    public void executing(Statement statement) { active.set(statement); }

    public void cancel() {
        stopping = true;
        Statement statement = active.get();
        if (statement == null) return;
        try { statement.cancel(); }
        catch (SQLException alreadyRetired) {
            // The worker may close its statement between get() and cancel(). The drain still
            // requires actual termination, so a failed cancellation can never permit early DB close.
        }
    }

    public boolean isOurCancellation(Throwable failure) {
        if (!stopping) return false;
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause())
            if (failure instanceof SQLException sql && sql.getErrorCode() == 9) return true; // SQLITE_INTERRUPT
        return false;
    }

    public final class Scope implements AutoCloseable {
        private final RegenQueryControl previous;
        private boolean closed;
        private Scope(RegenQueryControl previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            active.set(null);
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
