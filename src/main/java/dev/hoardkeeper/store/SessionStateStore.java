package dev.hoardkeeper.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.model.SessionSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Persists the resumable state of one scan session to a single JSON file, so a crashed or closed
 * game can pick a scan back up where it left off.
 *
 * <p>{@code save} is debounced to at most one write per 2 seconds: the resume index is updated far
 * more often than it needs to hit disk, and a scan can discover hundreds of candidates per second.
 * {@link #flush()} bypasses the debounce and always writes the most recently saved snapshot,
 * so callers can force a write on session end regardless of timing.
 *
 * <p>A snapshot for a session with thousands of candidates can run several hundred KB to ~1 MB, and
 * the debounce only bounds how <em>often</em> that gets rewritten, not how <em>long</em> a single
 * rewrite takes. {@code save}/{@code flush} are called from Minecraft's client thread, so — exactly
 * like {@link ContainerJsonlWriter} — the actual disk write happens on a single background daemon
 * thread (named {@code hoardkeeper-state}) and never blocks the caller. {@code flush()} still
 * has to guarantee the last snapshot handed to {@code save} is durably on disk before it returns
 * (callers rely on this at session end), so it submits the pending write and blocks on its
 * {@link Future} — bounded to 10 seconds — rather than firing it and forgetting.
 *
 * <p>Every write goes to a sibling temp file first, then is published with
 * {@link Files#move(Path, Path, java.nio.file.CopyOption...)} using {@code REPLACE_EXISTING} and
 * {@code ATOMIC_MOVE}. A crash or power loss mid-write must never leave a half-written resume index
 * on disk; the atomic rename means the target file is always either the old complete snapshot or the
 * new complete one, never a truncated mix of both. Symmetrically, {@link #load} treats a corrupt or
 * unreadable file as "no resumable session" rather than a crash: catching every exception and
 * returning {@link Optional#empty()} is deliberate, since a resume index is an optimisation, not a
 * source of truth the mod cannot function without.
 */
public final class SessionStateStore implements AutoCloseable {

    private static final Gson GSON = new GsonBuilder().create();
    private static final long DEBOUNCE_MILLIS = 2000;
    private static final long AWAIT_SECONDS = 10;

    /**
     * Sentinel for "no write has been scheduled yet", chosen so the debounce gate
     * {@code now - lastScheduledMs >= DEBOUNCE_MILLIS} is open on the very first {@code save}.
     *
     * <p>Deliberately NOT {@code Long.MIN_VALUE}: {@code System.currentTimeMillis() - Long.MIN_VALUE}
     * overflows to a large negative number, so the gate never opens, {@code lastScheduledMs} is never
     * advanced off the sentinel, and no periodic write ever happens for the lifetime of the session —
     * only {@link #flush()} would ever put the file on disk, and a hard crash mid-scan would lose the
     * whole resume index. This is the same overflow that shipped in the spike prototype as
     * {@code Integer.MIN_VALUE} tick sentinels; see {@code RateLimiter.NEVER} and
     * {@code ScanController.NEVER}, which encode the same rule for ticks.
     *
     * <p><b>The rule, in general form: no sentinel of any width may be {@code MIN_VALUE} where it is
     * subtracted from a current value.</b> Pick a value one window before zero instead, which is
     * unambiguously "long enough ago" without ever wrapping.
     */
    private static final long NEVER_SCHEDULED = -DEBOUNCE_MILLIS;

    private final Path file;
    private final ExecutorService executor;

    private final Object lock = new Object();
    private SessionSnapshot pending;
    private long lastScheduledMs = NEVER_SCHEDULED;

    public SessionStateStore(Path file) {
        this(file, Executors.newSingleThreadExecutor(daemonThreadFactory("hoardkeeper-state")));
    }

    /**
     * Test seam: lets tests hand in a custom executor (e.g. an artificially slow one) to prove that
     * {@link #flush()} genuinely waits for the write to land rather than firing-and-forgetting it.
     */
    SessionStateStore(Path file, ExecutorService executor) {
        this.file = file;
        this.executor = executor;
    }

    /**
     * Records the latest snapshot and, unless a write was scheduled less than 2 seconds ago, submits
     * it to the background writer thread. Returns immediately either way — never touches disk on the
     * calling thread. A snapshot that arrives inside the debounce window is kept in memory and will
     * be picked up by the next successful {@code save} or by {@link #flush()}.
     */
    public void save(SessionSnapshot s) {
        Runnable writeTask = null;
        synchronized (lock) {
            pending = s;
            long now = System.currentTimeMillis();
            if (now - lastScheduledMs >= DEBOUNCE_MILLIS) {
                lastScheduledMs = now;
                writeTask = () -> writeNow(s);
            }
        }
        if (writeTask != null) {
            executor.submit(writeTask);
        }
    }

    /**
     * Submits the most recently saved snapshot for writing, ignoring the debounce window, and blocks
     * (up to 10 seconds) until that write has actually completed — so the caller can rely on the
     * snapshot being on disk the moment this method returns.
     */
    public void flush() {
        SessionSnapshot snapshot;
        synchronized (lock) {
            snapshot = pending;
            if (snapshot == null) {
                return;
            }
            lastScheduledMs = System.currentTimeMillis();
        }
        awaitWrite(executor.submit(() -> writeNow(snapshot)));
    }

    private void awaitWrite(Future<?> future) {
        try {
            future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            HoardkeeperMod.LOGGER.error("Failed to flush session state file: {}", file, e);
        }
    }

    private void writeNow(SessionSnapshot s) {
        AtomicJson.write(file, s);
    }

    /**
     * Flushes the last snapshot to disk (if any — durably, per {@link #flush()}), then shuts the
     * background writer thread down and waits up to 10 seconds for it to terminate. A relog or a
     * clean game shutdown mid-scan must not lose state, so this is safe to call unconditionally.
     */
    @Override
    public void close() {
        flush();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                HoardkeeperMod.LOGGER.warn("Session state writer did not drain within 10s; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /**
     * Loads a previously saved snapshot. Returns {@link Optional#empty()} for a missing file, a
     * corrupt file, or any other read failure — never throws.
     */
    public static Optional<SessionSnapshot> load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String json = Files.readString(file);
            SessionSnapshot snapshot = GSON.fromJson(json, SessionSnapshot.class);
            return Optional.ofNullable(snapshot);
        } catch (Exception e) {
            // Deliberately broad: a corrupt or unreadable resume index means "no resumable session",
            // never a crash — the client thread must be able to call this unconditionally at startup.
            HoardkeeperMod.LOGGER.warn("Failed to load session state file: {}", file, e);
            return Optional.empty();
        }
    }

    /**
     * Writes one snapshot straight through, on the calling thread, with the same temp-file-then-atomic
     * -rename publication {@link #writeNow} uses. For callers that hold no store instance and have
     * exactly one write to make — an upload add-on stamping
     * {@code uploadedAt} onto a finished session from the upload thread. Spinning up a whole store
     * with its own executor for a single write would be the wrong shape, and duplicating the atomic
     * rename would be the wrong kind of wrong.
     *
     * <p>Returns whether the file is on disk. Never throws.
     */
    public static boolean writeOnce(Path file, SessionSnapshot snapshot) {
        return AtomicJson.write(file, snapshot);
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
