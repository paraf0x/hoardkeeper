package dev.hoardkeeper.model;

import java.util.List;

/**
 * The full resumable state of a scan session, written to disk periodically so a scan can survive a
 * restart. {@link #schemaVersion} lets a future format change detect and migrate old snapshots;
 * version 2 added {@link #uploadedAt}, and a version 1 file reads correctly as "never uploaded";
 * version 3 added {@link #areaMode} and {@link #chunkRadius}, and an absent mode means the radius
 * scan that every earlier snapshot was.
 *
 * <p>Plain data holder for JSON (de)serialisation — no behaviour, no Minecraft imports.
 */
public class SessionSnapshot {
    public int schemaVersion = 3;
    public String sessionId;
    public String server;
    /**
     * The realm this scan ran on, as the opaque key {@link dev.hoardkeeper.realm.RealmKey}
     * produces — or {@code null} for a session scanned before this field existed, and for one the
     * client could not identify. Absent travels to the server as an absent field, which a
     * single-realm instance resolves to the only realm it has; guessing here would risk filing a
     * base under a realm the scan cannot vouch for.
     */
    public String realm;
    public String dimension;
    public int[] origin;
    /**
     * The shape of the scanned area, {@code "RADIUS"} or {@code "CHUNKS"} — the half-width in blocks
     * of a chunk area's square when the mode is {@code CHUNKS}, so a reader that understands only a
     * radius still gets a usable number. {@link dev.hoardkeeper.scan.ScanArea} is the truth.
     */
    public int radius;
    /**
     * {@code "RADIUS"} or {@code "CHUNKS"}; {@code null} in every snapshot written before schema 3,
     * which is why {@link dev.hoardkeeper.scan.ScanArea#fromSnapshot} reads null as {@code RADIUS}.
     */
    public String areaMode;
    /** Rings of chunks around the origin's chunk when {@link #areaMode} is {@code CHUNKS}. */
    public int chunkRadius;
    public String startedAt;
    public String updatedAt;
    public String state;
    /**
     * {@code "storage"} or {@code "site"} — see {@code ScanSession#purpose}.
     *
     * <p>Defaulted rather than left null, and deliberately without a {@code schemaVersion} bump: a
     * snapshot written before this field existed was a storage scan, and Gson leaving the default
     * in place says exactly that. A null here would have to be interpreted at every read instead,
     * and the read that forgot would be the one that uploads a build site.
     */
    public String purpose = "storage";
    /**
     * When every container in this session reached the upload server, as ISO-8601 UTC — or {@code null} for
     * a session that has never been uploaded, which is what an absent field deserialises to and what
     * every snapshot written before schema 2 therefore means. Set only after the last batch of an
     * upload succeeded, so it answers exactly one question: may this session's directory be deleted
     * now? See an upload add-on, which stamps this field and then,
     * once it is stamped, deletes the session — the map has already taken what it needed from it.
     */
    public String uploadedAt;
    public Stats stats;
    public List<CandidateState> containers;

    public SessionSnapshot() {
    }

    /** Progress counters for a session, summarised for quick status reporting. */
    public static class Stats {
        public int known;
        public int scanned;
        public int failed;
        public int pending;
        public int chunksInRadius;
        public int chunksLoaded;

        public Stats() {
        }
    }
}
