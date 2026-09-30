package dev.hoardkeeper.measured;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.scan.ScanArea;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The projection rule: what one measurement does to the map. Design spec §4.
 *
 * <p>Pure — takes lists and returns a list, no Minecraft and no files — so the whole rights table
 * below is testable without a game, the way {@code ObservationGate} and {@code ObservationLog} are.
 *
 * <p><b>The source decides the rights</b> (spec §4.2):
 *
 * <table>
 *   <tr><th>Source</th><th>create</th><th>update</th><th>remove</th></tr>
 *   <tr><td>{@link Source#SCAN}</td><td>yes</td><td>yes</td><td>yes, inside its area</td></tr>
 *   <tr><td>{@link Source#CONTENT_UPDATE}</td><td><b>no</b></td><td>yes</td><td>no</td></tr>
 * </table>
 *
 * <p>The "no" is {@code ObservationGate.NOT_A_REGISTERED_CONTAINER} carried into the map: a content
 * update updates a container the storage already knows, it does not invent one. Without it a
 * shulker box set down for one trip would register itself here and defeat the gate built to
 * exclude it.
 *
 * <p><b>Identity is {@code pos} alone</b> — what {@code ContainerDiscovery.candidateAt}
 * canonicalises a double chest to, what {@code ObservationLog} compacts on, and what the search
 * index keys on.
 */
public final class MeasuredMap {

    /** Where a row came from, which is what decides what it may do. */
    public enum Source {
        SCAN,
        CONTENT_UPDATE
    }

    private MeasuredMap() {
    }

    /**
     * The map after {@code rows} — one measurement of {@code area} — have been projected onto it.
     *
     * @param complete whether the scan ended with nothing pending. Only a complete scan removes
     *                 anything (spec §4.3): one that stopped early did not look everywhere inside
     *                 its own area, and letting it clean up would delete what it merely never
     *                 reached. Ignored for {@link Source#CONTENT_UPDATE}, which never removes.
     */
    public static List<ScannedContainer> project(List<ScannedContainer> map,
                                                  List<ScannedContainer> rows,
                                                  ScanArea area,
                                                  boolean complete,
                                                  Source source) {
        Map<PosKey, ScannedContainer> byPos = new LinkedHashMap<>();
        for (ScannedContainer existing : map) {
            if (isPos(existing.pos)) {
                byPos.put(PosKey.of(existing.pos), existing);
            }
        }

        Map<PosKey, ScannedContainer> reported = new LinkedHashMap<>();
        for (ScannedContainer row : rows) {
            if (!isPos(row.pos)) {
                continue;
            }
            PosKey key = PosKey.of(row.pos);
            reported.put(key, row);
            ScannedContainer existing = byPos.get(key);
            if (existing == null) {
                // Spec §4.2: only a scan may bring a container into existence.
                if (source == Source.SCAN) {
                    byPos.put(key, row);
                }
            } else if (isNewer(row, existing)) {
                byPos.put(key, row);
            }
        }

        if (source == Source.SCAN && complete && area != null) {
            // Spec §4.3: inside the area it actually finished, what the scan did not report is gone.
            byPos.entrySet().removeIf(entry ->
                    !reported.containsKey(entry.getKey())
                            && area.coversBlock(entry.getKey().x(), entry.getKey().z()));
        }

        return new ArrayList<>(byPos.values());
    }

    private static boolean isPos(int[] pos) {
        return pos != null && pos.length == 3;
    }

    /**
     * Whether {@code candidate} is newer than {@code existing}. An unparseable timestamp sorts
     * older than every parseable one, so a corrupt row never displaces a good one — the guard
     * {@code ObservationOverlay} applied before this class existed.
     */
    private static boolean isNewer(ScannedContainer candidate, ScannedContainer existing) {
        long a = epochMillis(candidate.scannedAt);
        long b = epochMillis(existing.scannedAt);
        return a > b;
    }

    private static long epochMillis(String scannedAt) {
        if (scannedAt == null) {
            return Long.MIN_VALUE;
        }
        try {
            return Instant.parse(scannedAt).toEpochMilli();
        } catch (DateTimeParseException e) {
            return Long.MIN_VALUE;
        }
    }

    private record PosKey(int x, int y, int z) {
        static PosKey of(int[] pos) {
            return new PosKey(pos[0], pos[1], pos[2]);
        }
    }
}
