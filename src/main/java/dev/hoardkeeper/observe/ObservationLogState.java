package dev.hoardkeeper.observe;

/**
 * The {@code log.json} beside an observation log's {@code containers.jsonl}: who the log belongs to,
 * and how much of it has reached the upload server. Design spec §4.1 and §4.3.
 *
 * <p>Plain data holder for JSON (de)serialisation — no behaviour, no Minecraft imports, the same
 * shape {@code SessionSnapshot} takes. Published by {@code AtomicJson.write}, so a crash mid-write
 * can never leave a half-written watermark behind.
 *
 * <p><b>{@link #uploadedRows} is a count, not a timestamp</b>: how many rows from the start of the
 * file are known to have reached the server, so everything past it is pending. A clock that jumps
 * backwards would strand rows forever under a timestamp watermark; a count cannot. A file that
 * fails to parse is read as {@code uploadedRows = 0} (spec §10) — everything re-uploads, which the
 * server's upsert makes harmless — and never as "delete the log".
 */
public final class ObservationLogState {

    public int schemaVersion = 1;

    /** The raw server address, as {@code SessionSnapshot.server} records it — not the slug. */
    public String server;

    /**
     * The opaque realm key in force when this log was opened, or {@code null} when the client could
     * not tell. Absent travels to the upload server as an absent field, exactly as a scan's does.
     */
    public String realm;

    /** The full dimension id, e.g. {@code "minecraft:overworld"} — not the slug. */
    public String dimension;

    /** How many rows from the start of {@code containers.jsonl} have reached the server. */
    public int uploadedRows;

    /** When this file was last written, as an ISO-8601 instant. */
    public String updatedAt;

    public ObservationLogState() {
    }
}
