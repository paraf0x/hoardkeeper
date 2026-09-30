package dev.hoardkeeper.measured;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code measured.json} beside a map's {@code containers.jsonl}: which areas have been swept,
 * and which sessions have already been folded in. Design spec §3.3.
 *
 * <p>Plain data holder for JSON (de)serialisation — no behaviour, no Minecraft imports, the shape
 * {@code SessionSnapshot} and {@code ObservationLogState} take. Published by
 * {@code AtomicJson.write}, so a crash mid-write cannot leave half of it behind.
 *
 * <p><b>The areas are not bookkeeping.</b> They are what makes two questions answerable at all:
 * where a scan may remove entries (spec §4.3) and what counts as one storage (spec §5.1).
 */
public final class MeasuredMapState {

    public int schemaVersion = 1;

    /** The raw server address, as {@code SessionSnapshot.server} records it — not the slug. */
    public String server;

    /** The opaque realm key, or {@code null} when the client could not tell. */
    public String realm;

    /** The full dimension id, e.g. {@code "minecraft:overworld"} — not the slug. */
    public String dimension;

    /** When this file was last written, as an ISO-8601 instant. */
    public String updatedAt;

    /** Every area a projected scan covered, in the order they were projected. */
    public List<Area> areas = new ArrayList<>();

    /**
     * The ids of sessions already folded in. Ids, not paths: a session that uploaded and was
     * deleted must stay on this list, or a re-created directory of the same name would be counted
     * twice.
     */
    public List<String> projectedSessions = new ArrayList<>();

    public MeasuredMapState() {
    }

    /** One swept area. {@code complete} records whether it was authoritative — see spec §4.3. */
    public static final class Area {
        public String areaMode;
        public int[] origin;
        public int radius;
        public int chunkRadius;
        public String measuredAt;
        public boolean complete;

        public Area() {
        }
    }
}
