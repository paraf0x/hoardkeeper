package dev.hoardkeeper.store;

import dev.hoardkeeper.model.SessionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStateStoreTest {

    private static SessionSnapshot snapshot(String state) {
        SessionSnapshot s = new SessionSnapshot();
        s.sessionId = "sid";
        s.state = state;
        s.containers = List.of();
        s.stats = new SessionSnapshot.Stats();
        return s;
    }

    @Test
    void flushWritesImmediatelyEvenWhenDebounced(@TempDir Path dir) {
        Path file = dir.resolve("session.json");
        SessionStateStore store = new SessionStateStore(file);
        store.save(snapshot("RUNNING"));
        store.save(snapshot("FINISHED"));
        store.flush();

        assertEquals("FINISHED", SessionStateStore.load(file).orElseThrow().state);
    }

    @Test
    void loadReturnsEmptyForAMissingFile(@TempDir Path dir) {
        assertTrue(SessionStateStore.load(dir.resolve("nope.json")).isEmpty());
    }

    @Test
    void loadReturnsEmptyForCorruptJsonRatherThanThrowing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("session.json");
        Files.writeString(file, "{ this is not json");

        Optional<SessionSnapshot> loaded = SessionStateStore.load(file);

        assertTrue(loaded.isEmpty());
    }

    /**
     * The actual write happens on a background thread; {@code flush()} must block until that write
     * has genuinely landed on disk, not merely been submitted. An artificially slow executor makes a
     * fire-and-forget {@code flush()} observable: with no real wait, reading the file immediately
     * after {@code flush()} returns would race the delayed background write and see stale (or no)
     * content instead of "FINISHED".
     */
    @Test
    void flushWaitsForTheAsyncWriteToLandOnDiskRatherThanFiringAndForgetting(@TempDir Path dir) {
        Path file = dir.resolve("session.json");
        DelayingExecutor delayingExecutor = new DelayingExecutor(300);
        try {
            SessionStateStore store = new SessionStateStore(file, delayingExecutor);
            store.save(snapshot("RUNNING"));
            store.save(snapshot("FINISHED"));
            store.flush();

            assertEquals("FINISHED", SessionStateStore.load(file).orElseThrow().state);
        } finally {
            delayingExecutor.shutdownNow();
        }
    }

    /**
     * Regression for the {@code Long.MIN_VALUE} debounce sentinel.
     *
     * <p>{@code save} gates on {@code now - lastScheduledMs >= DEBOUNCE_MILLIS}. With
     * {@code Long.MIN_VALUE} as the initial value that subtraction overflows to a large negative
     * number, so the gate never opened, {@code lastScheduledMs} never advanced off the sentinel, and
     * <em>no</em> periodic write ever happened: only {@code flush()} put the file on disk. Measured
     * consequence: 26 rows in {@code containers.jsonl} with {@code session.json} still absent after
     * 40 seconds, i.e. a hard crash mid-scan lost the entire resume index.
     *
     * <p>Every other test in this class calls {@code flush()}, which is exactly why they all passed
     * against the buggy build. This one never calls it — the assertion is that {@code save} alone,
     * on a fresh store, lands on disk.
     *
     * <p>The executor runs the write inline, so "enough time has elapsed for the background write"
     * is not something this test has to wait for or guess at: when {@code save} returns, the write
     * has either happened or was never scheduled, and there is no timing window in which a passing
     * result could be luck.
     *
     * <p>The second half is what stops this passing for the wrong reason: an immediate follow-up
     * {@code save} must <em>not</em> reach disk, so simply deleting the debounce — which would also
     * make the first assertion pass — fails here instead.
     */
    @Test
    void aSaveOnAFreshStoreLandsOnDiskWithoutAnyFlush(@TempDir Path dir) {
        Path file = dir.resolve("session.json");
        SessionStateStore store = new SessionStateStore(file, new SameThreadExecutor());

        store.save(snapshot("RUNNING"));
        // Deliberately no flush() anywhere in this test.

        assertTrue(Files.isRegularFile(file),
                "a save on a fresh store must schedule a write; the debounce sentinel overflowed instead");
        assertEquals("RUNNING", SessionStateStore.load(file).orElseThrow().state);

        // ...and the debounce itself must still be there: a second save 0 ms later stays in memory.
        store.save(snapshot("FINISHED"));

        assertEquals("RUNNING", SessionStateStore.load(file).orElseThrow().state,
                "a save inside the 2 s debounce window must not reach disk");
    }

    /** Runs every submitted task inline on the calling thread, so writes are observable immediately. */
    private static final class SameThreadExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    /**
     * Delays every submitted task by a fixed amount before running it, so a caller that waits on the
     * returned future's completion (rather than just its submission) is observably different from one
     * that doesn't.
     */
    private static final class DelayingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private final long delayMs;

        DelayingExecutor(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                command.run();
            });
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
