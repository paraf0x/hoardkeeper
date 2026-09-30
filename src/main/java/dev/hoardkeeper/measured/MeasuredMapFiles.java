package dev.hoardkeeper.measured;

import java.nio.file.Path;

/**
 * Where one realm's measured map lives:
 * {@code <gameDir>/hoardkeeper/<serverSlug>/measured/<realmSlug>/<dimensionSlug>/}, holding
 * {@code containers.jsonl} and {@code measured.json}. Design spec §3.1.
 *
 * <p><b>{@code measured/} is deliberately not a session directory.</b> Every session enumerator —
 * {@code UploadTargets.listSessionDirs}, {@code ScanController.offerResume},
 * {@code MeasuredMapProjector.migrate} — reads a child's {@code session.json}. This directory has
 * none, so all of them skip it for free. The two file names below are therefore load bearing:
 * naming either one {@code session.json} would quietly enrol the map in every one of those
 * enumerations.
 *
 * <p>Keyed by realm, like {@code observed/} and unlike the scan sessions: a backend switch behind a
 * proxy changes {@code RealmKey}, and what follows must not land under the realm the player left.
 *
 * <p>Takes slugs, not raw values — the caller owns the security boundary {@code ScanStorage.slug}
 * documents, which is what keeps this class free of Minecraft.
 */
public final class MeasuredMapFiles {

    private static final String MEASURED = "measured";

    private MeasuredMapFiles() {
    }

    public static Path dir(Path gameDir, String serverSlug, String realmSlug, String dimensionSlug) {
        return gameDir.resolve("hoardkeeper")
                .resolve(serverSlug)
                .resolve(MEASURED)
                .resolve(realmSlug)
                .resolve(dimensionSlug);
    }

    /** The map itself: one {@code ScannedContainer} per line, newest per position wins. */
    public static Path containers(Path dir) {
        return dir.resolve("containers.jsonl");
    }

    /** The swept areas and the sessions already folded in — never named {@code session.json}. */
    public static Path state(Path dir) {
        return dir.resolve("measured.json");
    }
}
