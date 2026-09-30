package dev.hoardkeeper.measured;

import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.SessionSnapshot;
import dev.hoardkeeper.realm.RealmKey;
import dev.hoardkeeper.realm.RealmMatch;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.store.ScanStorage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The measured map for the client the player is actually on. Design spec §8.
 *
 * <p>Thin on purpose: every decision it reaches for lives in {@link MeasuredMap},
 * {@link StorageClusters} and {@link MeasuredMapStore}, which are pure or file-only and tested
 * there. What is left here is deriving server, realm and dimension from a live client — the same
 * split {@code PassiveObserver} keeps, and the reason this class has no unit test of its own.
 */
public final class MeasuredMapProjector {

    /** The realm slug for a client that cannot tell which realm it is on. */
    private static final String UNKNOWN_REALM = "unknown-realm";

    private MeasuredMapProjector() {
    }

    /** The map directory for this client, or {@code null} when there is no level to name one. */
    public static Path dirFor(Minecraft mc) {
        ClientLevel level = mc == null ? null : mc.level;
        if (level == null) {
            return null;
        }
        return dirFor(FabricLoader.getInstance().getGameDir(), ScanController.serverAddress(mc),
                RealmKey.get().current(), level.dimension().identifier().toString());
    }

    /**
     * The map directory for the server, realm and dimension a <em>session snapshot</em> names,
     * rather than for wherever the player happens to be standing. {@code null} when the snapshot
     * names no server or no dimension, which is a session too corrupt to place.
     *
     * <p>Exists for {@code SessionUploader}: it decides whether a session it just uploaded may be
     * deleted, and the session it is holding may well have been scanned in another dimension —
     * {@code /hoard upload all} is dimension-agnostic. Asking {@link #dirFor(Minecraft)}
     * there would consult the Overworld's map about a Nether session and find nothing.
     *
     * <p>{@code ScanStorage.slug} is idempotent, so passing the raw values a snapshot records
     * produces exactly the directory a projection from the same session wrote into.
     */
    public static Path dirFor(Path gameDir, String server, String realm, String dimension) {
        if (server == null || dimension == null) {
            return null;
        }
        return MeasuredMapFiles.dir(gameDir, ScanStorage.slug(server),
                realm == null ? UNKNOWN_REALM : ScanStorage.slug(realm),
                ScanStorage.slug(dimension));
    }

    /** The store for this client, or {@code null} when there is no level. */
    public static MeasuredMapStore storeFor(Minecraft mc) {
        Path dir = dirFor(mc);
        return dir == null ? null : new MeasuredMapStore(dir);
    }

    /**
     * Projects the scan session at {@code sessionDir} <b>only if it has never been folded in</b>.
     * The form the migration of spec §9 uses: it walks every session on disk on every join, and
     * {@code projectedSessions} is what stops the same rows being folded in twice.
     *
     * <p>Silent and non-fatal: a session that cannot be projected stays on disk and is tried again.
     */
    public static void projectSessionOnce(Minecraft mc, Path sessionDir) {
        project(mc, sessionDir, false);
    }

    /**
     * Projects the scan session at {@code sessionDir} <b>whether or not it is already on
     * {@code projectedSessions}</b>. The form a scan that has just ended uses.
     *
     * <p><b>Why the guard has to be bypassed here.</b> A resumed scan reuses the interrupted
     * session's directory <em>and</em> its id, so the join that offered the resume has already
     * folded the partial rows in and written that id down. Honouring the guard at the end of the
     * resumed scan would therefore drop everything found after the resume — and the auto-upload
     * that follows would then delete the directory holding it. Re-projecting rows the map already
     * has is idempotent (newest-per-position, and an id already on {@code projectedSessions} is not
     * added twice), so forcing costs nothing and is the only thing that makes a resumed scan's
     * second half reach the map. It is also what runs the §4.3 cleanup the completed scan is
     * entitled to and the interrupted one was not.
     */
    public static void projectFinishedScan(Minecraft mc, Path sessionDir) {
        project(mc, sessionDir, true);
    }

    private static void project(Minecraft mc, Path sessionDir, boolean force) {
        MeasuredMapStore store = storeFor(mc);
        if (store == null || sessionDir == null) {
            return;
        }
        SessionSnapshot snapshot = ScanStorage.readSnapshot(sessionDir);
        if (snapshot == null || !"storage".equals(snapshot.purpose)) {
            return;
        }
        String sessionId = snapshot.sessionId == null
                ? String.valueOf(sessionDir.getFileName()) : snapshot.sessionId;
        if (!force && store.hasProjected(sessionId)) {
            return;
        }
        List<ScannedContainer> rows =
                ScanStorage.readContainers(sessionDir.resolve("containers.jsonl"));
        ScanArea area = ScanArea.fromSnapshot(snapshot.areaMode, snapshot.origin, snapshot.radius,
                snapshot.chunkRadius);
        boolean complete = snapshot.stats != null && snapshot.stats.pending == 0;
        if (store.projectScan(rows, area, complete, sessionId, snapshot.server, snapshot.realm,
                snapshot.dimension)) {
            HoardkeeperMod.LOGGER.info("Projected scan {} onto the measured map ({} row(s), complete={})",
                    sessionId, rows.size(), complete);
        }
    }

    /**
     * Folds every scan session still on disk into the map, oldest first, skipping any already on
     * {@code projectedSessions}. Spec §9: nobody loses the stock they have measured when they
     * update, and oldest-first is what makes the newest measurement win.
     *
     * <p>Called on every join, not just the first one a version with this class ever sees: a scan
     * cut short by a disconnect or a realm change never gets to project itself — {@code
     * ScanController.onDisconnect} and {@code onRealmChanged} both skip {@code stop()} entirely, so
     * an interrupted scan's rows would otherwise sit on disk forever, invisible to the search. This
     * is what catches them up, and running it unconditionally is what makes catching up eventually
     * happen rather than depending on the one join that happened to follow an update.
     * {@link #projectSessionOnce}'s own {@code hasProjected} check is what keeps a repeat call
     * here from doing any work the second time.
     *
     * <p><b>Scoped to this dimension and realm</b>, per spec §9's "for a server, realm and
     * dimension" — {@link #projectSessionOnce} trusts its caller for that: it always writes into
     * {@code storeFor(mc)}, the client's <em>current</em> dimension and realm, whichever session it
     * is handed. {@code serverDir} holds every session for the server regardless of which dimension
     * or realm it was scanned in, so an unfiltered walk would fold, say, a Nether session into
     * whichever map the player happens to join into first. A session whose own dimension does not
     * match, or whose realm positively disagrees (see {@link RealmMatch}), is left for the join
     * that actually lands in it.
     *
     * <p>A session the old retention already deleted is gone and cannot be recovered locally. This
     * is a floor, not a restoration.
     */
    public static void migrate(Minecraft mc) {
        MeasuredMapStore store = storeFor(mc);
        ClientLevel level = mc == null ? null : mc.level;
        if (store == null || level == null) {
            return;
        }
        Path serverDir = ScanStorage.serverDir(FabricLoader.getInstance().getGameDir(),
                ScanStorage.slug(ScanController.serverAddress(mc)));
        if (!Files.isDirectory(serverDir)) {
            return;
        }
        String dimension = level.dimension().identifier().toString();
        String realm = RealmKey.get().current();
        try (Stream<Path> dirs = Files.list(serverDir)) {
            List<Path> here = dirs.filter(Files::isDirectory)
                    .filter(dir -> belongsHere(dir, dimension, realm))
                    .sorted()
                    .toList();
            here.forEach(dir -> projectSessionOnce(mc, dir));
            // Spec 2026-09-30-hoardkeeper-split-design.md §7: what the map now holds need not stay.
            here.forEach(dir -> SessionPruner.pruneIfDone(mc, store, dir));
        } catch (IOException e) {
            HoardkeeperMod.LOGGER.warn("Could not migrate scan sessions in {} onto the map",
                    serverDir, e);
        }
    }

    /**
     * Whether the session at {@code dir} was scanned in the dimension and realm a migration is
     * currently folding into. An unreadable snapshot is left alone rather than guessed at —
     * {@link #projectSessionOnce} would read it again, and fail the same way, the next time a join
     * actually matches it.
     */
    private static boolean belongsHere(Path dir, String dimension, String realm) {
        SessionSnapshot snapshot = ScanStorage.readSnapshot(dir);
        return snapshot != null && dimension.equals(snapshot.dimension)
                && RealmMatch.resumable(realm, snapshot.realm);
    }

    /**
     * Projects one observed container. The gate has already established that the map holds this
     * position, so this can only ever update — spec §4.2.
     */
    public static void projectObservation(Minecraft mc, ScannedContainer row) {
        MeasuredMapStore store = storeFor(mc);
        if (store != null) {
            store.appendContentUpdate(row);
        }
    }

    /**
     * Brings a container opened by hand into the map on a server where the scanner may not act —
     * spec 2026-09-30-hoardkeeper-split-design.md §4. There no scan will ever measure an area, and
     * only a scan may create a container (spec §4.2 of the map design), so without this the map
     * stays empty for good and search, tooltips and the peek card never have anything to say.
     *
     * <p>It is filed as a measurement of the 3×3 chunks around the container — enough for "the
     * storage you are standing in" to find it from beside it — and as <em>incomplete</em>, so it
     * can never remove anything the way a finished scan cleans up its own area. The same chunks
     * opened again merge into one area entry rather than adding another.
     */
    public static void projectHandObservation(Minecraft mc, ScannedContainer row) {
        MeasuredMapStore store = storeFor(mc);
        if (store == null || row == null || row.pos == null || row.pos.length != 3 || mc.level == null) {
            return;
        }
        store.projectScan(java.util.List.of(row), ScanArea.ofChunks(row.pos[0], row.pos[2], 1), false, null,
                ScanController.serverAddress(mc), RealmKey.get().current(),
                mc.level.dimension().identifier().toString());
    }
}
