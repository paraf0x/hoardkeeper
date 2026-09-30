package dev.hoardkeeper.scan;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether to tell the player the storage they just walked into was measured a long time ago, and how
 * many days ago that was. Design spec §7, re-grounded on the measured map that replaced sessions as
 * the map's source of truth (see below) — {@code ScanController} is the only caller.
 *
 * <p><b>Spec §7 predates the measured map and names things that no longer exist.</b> It was written
 * against per-session {@code sessionCoveringPlayer}/{@code snapshot.purpose}/{@code updatedAt}, all
 * of which are gone now that the map is cumulative rather than session-shaped. The six clauses
 * re-ground onto what actually exists today:
 * <ol>
 *   <li><b>"a session S where the previous check returned null or a different session"</b> becomes
 *       <i>the player entered a cluster</i> ({@link StorageClusters#covering}), having previously
 *       been in none or a different one — level-triggered on the previous tick's cluster id exactly
 *       as the spec's session id was, so a tick landing badly can never miss an entry and standing
 *       still never re-nudges. A cluster is identified by a canonical string built from the areas
 *       that make it up ({@link #clusterId}) rather than by a session id, because a session belongs
 *       to one scan and the map's clusters outlive every session that ever measured them.</li>
 *   <li><b>"S is a storage scan"</b> falls away entirely. The measured map only ever holds rows a
 *       storage scan produced — a build site's chests are excluded from projection at the source
 *       ({@code MeasuredMapProjector.project} refuses anything whose {@code purpose} is not
 *       {@code "storage"}) — so every cluster this class is ever asked about already passed that
 *       test before this class exists to ask a second time.</li>
 *   <li><b>"{@code snapshot.updatedAt} is older than the threshold"</b> becomes <i>the newest
 *       {@code measuredAt} among the cluster's areas</i> is older than the threshold — there is no
 *       single session left to carry an {@code updatedAt}, and "when was this storage last measured"
 *       can only mean the newest of however many scans have touched it. {@link #newestScannedAt}
 *       does that reduction over the cluster's area timestamps; {@link #decide} takes the one
 *       timestamp it produces and trusts it rather than re-deriving it, which is what keeps a hall
 *       half of which was rescanned yesterday from reading as overdue because some other area in it
 *       is ancient — the caller ({@code ScanController}) is what actually chains the two together.
 *       <b>Deliberately not the newest {@code scannedAt} among the cluster's rows</b> — an earlier
 *       version of this re-grounding read it that way, and it was wrong: passive observation
 *       (on by default) stamps a fresh {@code scannedAt} on every hand-opened chest, so a row's
 *       timestamp answers "when was this storage last touched", not "when was it last measured". A
 *       storage a player actually uses would then never read as stale, which is backwards — see
 *       {@code MeasuredMapStore.MeasuredArea}, which no observation ever writes to.</li>
 * </ol>
 * Clauses 4, 5 and 6 apply as written and are split across this class and {@code ScanController}
 * exactly as spec §8's table says: this class is clauses 1, 3 and 5; {@code ScanController} is 4 and
 * 6, because both are about state this class has no way to see (whether a scan is running, whether a
 * session is loaded, whether {@code ScanController.offerResume} already spoke this join) and folding
 * them in here would mean handing this class a live {@code ScanController} to ask, which is exactly
 * the Minecraft-shaped dependency it exists to not have.
 *
 * <p><b>Pure.</b> No Minecraft import, no clock of its own — {@code nowMillis} arrives as an
 * argument, exactly like {@code IdleCountdown} and {@code MeasuredMap} keep themselves testable
 * without a running game. Every clause below is a return statement a unit test can trigger on
 * purpose.
 *
 * <p><b>Offers, never acts.</b> {@link #decide} only ever answers "say this many days", never sends
 * anything — see {@code ScanChat.rescanOffer} and its {@code ClickEvent.RunCommand}, and the same
 * rule {@code ScanController.offerResume} already follows and for the same reason: a mod that starts
 * sending interaction packets because it decided a scan was old on its own is a mod that gets an
 * account actioned.
 */
public final class RescanNudge {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000L;

    private RescanNudge() {
    }

    /** Whether the rescan offer is switched on at all — both the flag and a positive threshold. */
    public static boolean enabled(boolean reminder, int days) {
        return reminder && days > 0;
    }

    /**
     * The number of days to say the storage covering the player was last measured, or
     * {@link OptionalLong#empty()} when nothing should be said this tick.
     *
     * @param previousClusterId the cluster id ({@link #clusterId}) the player was standing in on the
     *                          previous check, or {@code null} for "in none". Compared by value, not
     *                          identity — a fresh read of the same unchanged map must compare equal
     *                          to the tick before it.
     * @param currentClusterId  the cluster id the player is standing in right now, or {@code null}
     *                          for "in none". {@code null} always answers empty: there is no storage
     *                          to say anything about.
     * @param newestScannedAtIso the newest {@code measuredAt} among {@code currentClusterId}'s areas,
     *                          in ISO-8601, or {@code null}/unparseable when it is not known. An
     *                          unknown age is never treated as an old one (design spec §10) — an area
     *                          that cannot say when it was measured must never be the reason a player
     *                          is told it is stale.
     * @param nowMillis         the caller's clock, so this stays a pure function of its arguments.
     * @param thresholdDays     {@code rescanSuggestAfterDays} — a storage must be at least this many
     *                          days past its newest measurement before it is worth mentioning.
     * @param alreadyNudged     cluster ids already nudged this connection (clause 5). Read only —
     *                          the caller adds {@code currentClusterId} to it after a nudge is
     *                          actually sent, since this class has no way to know the nudge reached
     *                          the player rather than being, say, thrown away by a caller that chose
     *                          not to print it.
     */
    public static OptionalLong decide(String previousClusterId, String currentClusterId,
                                       String newestScannedAtIso, long nowMillis,
                                       int thresholdDays, Set<String> alreadyNudged) {
        if (currentClusterId == null) {
            // Standing in no measured area at all: there is no storage this could be about.
            return OptionalLong.empty();
        }
        if (currentClusterId.equals(previousClusterId)) {
            // Level-triggered on the previous cluster, exactly as spec §7 clause 1 was level-triggered
            // on the previous session: still standing in the same place is not a fresh entry, so a
            // player who stops walking is nudged once, not once a second for as long as they stay.
            return OptionalLong.empty();
        }
        if (alreadyNudged.contains(currentClusterId)) {
            // Clause 5: walking in and out of the same hall four times must be one line, not four.
            return OptionalLong.empty();
        }

        Instant newest = parseInstant(newestScannedAtIso);
        if (newest == null) {
            // Clause 3's error handling (design spec §10): a missing or corrupt timestamp reads as
            // "unknown", never as "very old" — an unknown age must never be the reason a player is
            // told their storage is stale.
            return OptionalLong.empty();
        }

        long ageMillis = nowMillis - newest.toEpochMilli();
        if (ageMillis < 0) {
            // A clock behind the file's timestamp (design spec §10): a negative age fails the
            // threshold check below on its own, but returning here says why rather than relying on
            // thresholdDays always being positive to make it so.
            return OptionalLong.empty();
        }

        long days = ageMillis / DAY_MILLIS;
        if (days < thresholdDays) {
            // Clause 3: not old enough yet to be worth mentioning.
            return OptionalLong.empty();
        }
        return OptionalLong.of(days);
    }

    /**
     * The newest of {@code measuredAtValues}, in ISO-8601, or {@code null} when none of them parse —
     * the reduction clause 3 is re-grounded on (see the class javadoc): "when was this storage last
     * measured" can only mean the newest of however many scans have touched it, never some other
     * value picked by list order.
     *
     * <p><b>Takes area timestamps, not rows.</b> This used to fold over each row's {@code
     * scannedAt}, which is exactly the bug fix round 2 (finding C-1/I-1) removed: an observation
     * refreshes a row's {@code scannedAt} on every hand-opened chest, so that reduction answered "last
     * touched", not "last measured", and a storage a player actually used would never read as stale.
     * The caller now passes {@code MeasuredMapStore.MeasuredArea.measuredAt()} for the areas in the
     * player's cluster instead — a value only an actual scan projection ever writes.
     *
     * <p>Fed straight into {@link #decide}'s {@code newestScannedAtIso} parameter, which trusts
     * whatever this method hands it rather than re-deriving it — the split exists so each half is
     * its own unit-testable clause: this method is "which timestamp wins", {@link #decide} is "is the
     * winner old enough".
     *
     * <p>A missing or unparseable timestamp is skipped, never chosen and never fatal to the values
     * around it — a single corrupt entry in an otherwise-healthy cluster must not make the whole
     * reduction answer {@code null} and read as "unknown age" when a perfectly good newest timestamp
     * sits right beside it.
     */
    public static String newestScannedAt(List<String> measuredAtValues) {
        String newest = null;
        Instant newestInstant = null;
        for (String measuredAt : measuredAtValues) {
            Instant parsed = parseInstant(measuredAt);
            if (isAfter(parsed, newestInstant)) {
                newestInstant = parsed;
                newest = measuredAt;
            }
        }
        return newest;
    }

    /**
     * A canonical identity for the connected component {@code cluster} of measured areas in
     * {@code dimension}, stable across ticks for as long as the areas making it up do not change, and
     * independent of the order {@link StorageClusters#covering}'s breadth-first walk happened to
     * produce them in.
     *
     * <p><b>Why an area-derived id rather than a session id.</b> Spec §7 clause 1 originally compared
     * session ids, but a session belongs to one scan and is gone the moment
     * {@code ScanController.reset()} drops it, while the cluster a player is standing in outlives
     * every session that ever measured it — it is a property of the map, not of any one scan.
     * {@link ScanArea} is already a record with value equality (see its own javadoc: two scans of
     * the same chunk-aligned spot are the identical area, not merely similar ones), so the areas
     * that make up a cluster are exactly the stable, comparable-by-value identity clause 1 needs:
     * unchanged between two ticks that read the same unchanged map, and different the moment a scan
     * genuinely changes what belongs to the cluster.
     *
     * <p><b>{@code dimension} is folded in for the same reason fix round 1 (finding I-2) cleared the
     * nudge state on a realm change.</b> The geometry alone is pure coordinates — an Overworld base
     * and a Nether hub both scanned around chunk (0,0) produce the identical {@link #areaKey} strings,
     * so without the dimension they would share one id, and nudging one would permanently suppress the
     * other for the rest of the connection the moment a player carrying that id in
     * {@code nudgedClusterIds} walked through the portal. There is no equivalent event to clear on —
     * unlike a realm switch, a dimension change is not a fresh connection — so the id itself carries
     * the distinction instead.
     *
     * <p>{@code null} for an empty cluster ("standing in no measured area"), which is what
     * {@link StorageClusters#covering} itself returns for that case and what {@link #decide} treats
     * as "nothing to nudge about" — {@code dimension} is irrelevant to an id that is already
     * "nothing", so this still returns {@code null} regardless of what {@code dimension} is.
     */
    public static String clusterId(List<ScanArea> cluster, String dimension) {
        if (cluster == null || cluster.isEmpty()) {
            return null;
        }
        String areasKey = cluster.stream()
                .map(RescanNudge::areaKey)
                .sorted()
                .collect(Collectors.joining("|"));
        return dimension + "@" + areasKey;
    }

    private static String areaKey(ScanArea area) {
        return area.mode() + ":" + area.originX() + "," + area.originZ() + ","
                + area.radius() + "," + area.chunkRadius();
    }

    /** {@code null} for a missing or unparseable timestamp — never an exception, and never a guess. */
    private static Instant parseInstant(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * {@code true} when {@code instant} is known and strictly newer than {@code other}. Duplicated
     * from {@code SessionSnapshotCodec.isAfter} rather than imported: that class reaches
     * {@code net.minecraft.core.BlockPos} for an unrelated method, and this one may import no
     * Minecraft type at all, directly or by way of a helper that carries one (see the class javadoc
     * and {@code PositionIndex}'s own javadoc, which duplicates the same method for the same reason).
     */
    private static boolean isAfter(Instant instant, Instant other) {
        return instant != null && (other == null || instant.isAfter(other));
    }
}
