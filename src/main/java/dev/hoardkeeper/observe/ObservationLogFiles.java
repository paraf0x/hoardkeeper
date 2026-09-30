package dev.hoardkeeper.observe;

import java.nio.file.Path;

/**
 * Where one realm's observation log lives on disk:
 * {@code <gameDir>/hoardkeeper/<serverSlug>/observed/<realmSlug>/<dimensionSlug>/}, holding
 * {@code containers.jsonl} and {@code log.json}. Design spec §4.1.
 *
 * <p><b>{@code observed/} is deliberately not a session directory.</b>
 * {@code UploadTargets.listSessionDirs}, {@code ScanController.offerResume} and
 * {@code MeasuredMapProjector.migrate} all enumerate the children of {@code <serverSlug>/} and read
 * each one's {@code session.json}. A log has none, so every one of them skips it without knowing it
 * exists —
 * which is what stops a log from ever being resumed, exported, offered as "the newest scan", or
 * swept up by {@code upload all} — and {@code upload all} is exactly what would otherwise put it on
 * the path a session takes once uploaded: deleted, the instant every batch lands, by
 * {@code SessionUploader} (design spec §6) rather than kept around and superseded the way the old
 * {@code SessionRetention} once decided that. A mod build older than this feature ignores the
 * directory entirely, so two builds can share a game directory. The two file names below are
 * therefore load bearing, not cosmetic: renaming {@code log.json} to {@code session.json} would
 * quietly enrol every log in all three of those enumerations.
 *
 * <p><b>Keyed by realm</b>, which the scan session directories are not — that is the known
 * proxy-realm defect, and a new layout should not inherit it. A backend switch behind a proxy
 * changes {@code RealmKey}, and the observations that follow land in a different log rather than
 * under the realm the player left.
 *
 * <p>Takes slugs, not raw values. The caller passes what {@code ScanStorage.slug} produced (and
 * {@code "unknown-realm"} when the client cannot tell which realm it is on), which is what keeps
 * this class free of Minecraft and testable without a game — and it means the caller, not this
 * class, owns the security boundary {@code ScanStorage.slug} documents.
 */
public final class ObservationLogFiles {

    /** The subdirectory of {@code <serverSlug>/} that no session enumerator will ever look inside. */
    private static final String OBSERVED = "observed";

    private ObservationLogFiles() {
    }

    /**
     * The directory holding the observation log for one server, realm and dimension. Every argument
     * except {@code gameDir} must already be slugged — see the class javadoc.
     */
    public static Path dir(Path gameDir, String serverSlug, String realmSlug, String dimensionSlug) {
        return gameDir.resolve("hoardkeeper")
                .resolve(serverSlug)
                .resolve(OBSERVED)
                .resolve(realmSlug)
                .resolve(dimensionSlug);
    }

    /** The append-only row file: one {@code ScannedContainer} per line, exactly as a scan writes. */
    public static Path containers(Path logDir) {
        return logDir.resolve("containers.jsonl");
    }

    /** The log's own state, {@link ObservationLogState} — never named {@code session.json}. */
    public static Path state(Path logDir) {
        return logDir.resolve("log.json");
    }
}
