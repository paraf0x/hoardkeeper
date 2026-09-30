package dev.hoardkeeper.store;

import com.google.gson.Gson;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.model.ScannedContainer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Appends one compact JSON line per scanned container to a JSONL file.
 *
 * <p>Serialisation is compact, not pretty-printed: a 5 000-container scan must never rewrite the
 * bulk file, so every container is written as a single self-contained line. Compact output is what
 * guarantees a container's JSON can never itself contain a raw newline, which would otherwise
 * corrupt the line-per-record format.
 *
 * <p>Writes run on a single background daemon thread so the caller — Minecraft's client thread —
 * never blocks on disk I/O. A failed write is logged and swallowed inside the writer task rather than
 * propagated, because an unhandled exception on the client thread would crash the game.
 */
public final class ContainerJsonlWriter implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final BufferedWriter writer;
    private final ExecutorService executor;

    public ContainerJsonlWriter(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open container JSONL file: " + file, e);
        }
        this.executor = Executors.newSingleThreadExecutor(daemonThreadFactory("hoardkeeper-writer"));
    }

    /**
     * Queues a container to be appended as one compact JSON line. Returns immediately; the actual
     * write happens on the background writer thread.
     */
    public void append(ScannedContainer c) {
        executor.submit(() -> {
            try {
                writer.write(GSON.toJson(c));
                writer.write("\n");
                writer.flush();
            } catch (IOException e) {
                HoardkeeperMod.LOGGER.error("Failed to append scanned container to JSONL file", e);
            }
        });
    }

    /**
     * Blocks until every {@link #append} queued so far has actually hit the file.
     *
     * <p>Each append already flushes the {@link BufferedWriter} as its last act, so "written" and
     * "durable" coincide per line — what this adds is ordering with respect to the caller: the
     * executor is single-threaded, so an empty task submitted now cannot run until every append
     * submitted before it has finished. Used by the report export, which reads the very file this
     * writer is appending to and would otherwise silently miss the last container or two of a
     * running scan.
     */
    public void flush() {
        try {
            executor.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            HoardkeeperMod.LOGGER.error("Failed to drain the container JSONL writer", e);
        }
    }

    /**
     * Shuts the writer thread down, waits up to 10 seconds for pending writes to drain, then closes
     * the underlying file.
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                HoardkeeperMod.LOGGER.warn("Container writer did not drain within 10s; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        try {
            writer.close();
        } catch (IOException e) {
            HoardkeeperMod.LOGGER.error("Failed to close container JSONL file", e);
        }
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
