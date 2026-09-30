package dev.hoardkeeper.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.model.CandidateState;
import dev.hoardkeeper.model.ScanReport;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.model.ScannedItem;
import dev.hoardkeeper.model.SessionSnapshot;
import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.ContainerStatus;
import dev.hoardkeeper.scan.FailReason;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.scan.ScanSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds and writes a scan's {@code report.json}: the pure half of
 * {@link dev.hoardkeeper.scan.ScanController#export} — the failures and skips read off either a
 * live session's candidates or a snapshot loaded from disk, and the final aggregate-and-write to
 * disk. Pure — no Minecraft classes, no client — so it can be tested without a running game.
 */
public final class SessionExporter {

    private SessionExporter() {
    }

    /** Writes {@code report.json} — pretty-printed, because a report is meant to be read by a human. */
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * The five header fields a report needs about the session it describes, from either a live
     * session or a snapshot on disk — so {@link dev.hoardkeeper.scan.ScanController#export} does
     * not have to build a full {@code SessionSnapshot} (one {@code CandidateState} per candidate,
     * thousands of them) just to read five values off it.
     */
    public record Header(String sessionId, String server, String dimension, int[] origin, ScanArea area) {

        public static Header of(ScanSession session) {
            return new Header(session.sessionId, session.server, session.dimension,
                    session.origin, session.area);
        }

        public static Header of(SessionSnapshot snapshot) {
            return new Header(snapshot.sessionId, snapshot.server, snapshot.dimension,
                    snapshot.origin,
                    ScanArea.fromSnapshot(snapshot.areaMode, snapshot.origin, snapshot.radius,
                            snapshot.chunkRadius));
        }
    }

    /** Failures worth reporting from live candidates — everything {@code FAILED} bar local skips. */
    public static List<ScanReport.FailureEntry> failuresOf(List<ContainerCandidate> candidates) {
        List<ScanReport.FailureEntry> failures = new ArrayList<>();
        for (ContainerCandidate candidate : candidates) {
            if (candidate.status != ContainerStatus.FAILED || candidate.failReason == null
                    || candidate.failReason.isLocalSkip()) {
                continue;
            }
            failures.add(failureEntry(candidate.pos, candidate.kind.id(), candidate.failReason.name()));
        }
        return failures;
    }

    /** The same list, from a snapshot, for exporting a session that is no longer in memory. */
    public static List<ScanReport.FailureEntry> failuresOf(SessionSnapshot snapshot) {
        List<ScanReport.FailureEntry> failures = new ArrayList<>();
        if (snapshot.containers == null) {
            return failures;
        }
        for (CandidateState state : snapshot.containers) {
            if (!"FAILED".equals(state.status) || state.failReason == null
                    || !SessionSnapshotCodec.isPos(state.pos)) {
                continue;
            }
            FailReason reason = SessionSnapshotCodec.parseReason(state.failReason);
            // An unparseable reason (a snapshot from another version) is reported rather than
            // dropped: only reasons known to be local skips are filtered out.
            if (reason != null && reason.isLocalSkip()) {
                continue;
            }
            failures.add(failureEntry(state.pos, state.kind, state.failReason));
        }
        return failures;
    }

    /**
     * The other half of {@link #failuresOf}: the containers this scan decided locally not to open.
     * Exactly the ones {@code failuresOf} filters out, so between them every {@code FAILED}
     * candidate reaches the report through one list or the other and none is silently dropped.
     */
    public static List<ScanReport.FailureEntry> skippedOf(List<ContainerCandidate> candidates) {
        List<ScanReport.FailureEntry> skipped = new ArrayList<>();
        for (ContainerCandidate candidate : candidates) {
            if (candidate.status != ContainerStatus.FAILED || candidate.failReason == null
                    || !candidate.failReason.isLocalSkip()) {
                continue;
            }
            skipped.add(failureEntry(candidate.pos, candidate.kind.id(), candidate.failReason.name()));
        }
        return skipped;
    }

    /** The same list, from a snapshot, for exporting a session that is no longer in memory. */
    public static List<ScanReport.FailureEntry> skippedOf(SessionSnapshot snapshot) {
        List<ScanReport.FailureEntry> skipped = new ArrayList<>();
        if (snapshot.containers == null) {
            return skipped;
        }
        for (CandidateState state : snapshot.containers) {
            if (!"FAILED".equals(state.status) || state.failReason == null
                    || !SessionSnapshotCodec.isPos(state.pos)) {
                continue;
            }
            FailReason reason = SessionSnapshotCodec.parseReason(state.failReason);
            // Mirror failuresOf: an unparseable reason (a snapshot from another version) counts as a
            // real failure there, so it must not also count as a skip here.
            if (reason == null || !reason.isLocalSkip()) {
                continue;
            }
            skipped.add(failureEntry(state.pos, state.kind, state.failReason));
        }
        return skipped;
    }

    /** How many containers a snapshot's scan knew about, preferring its own recorded stat. */
    public static int knownOf(SessionSnapshot snapshot) {
        if (snapshot.stats != null && snapshot.stats.known > 0) {
            return snapshot.stats.known;
        }
        return snapshot.containers == null ? 0 : snapshot.containers.size();
    }

    private static ScanReport.FailureEntry failureEntry(int[] pos, String kind, String reason) {
        ScanReport.FailureEntry entry = new ScanReport.FailureEntry();
        entry.pos = pos;
        entry.kind = kind;
        entry.reason = reason;
        return entry;
    }

    /** Every item id in {@code items}, including the contents of scanned shulker boxes and bundles. */
    public static void collectItemIds(List<ScannedItem> items, Set<String> ids) {
        if (items == null) {
            return;
        }
        for (ScannedItem item : items) {
            if (item.id != null) {
                ids.add(item.id);
            }
            collectItemIds(item.contents, ids);
        }
    }

    /**
     * Assembles the report and writes it to {@code dir/report.json}. Returns the path, or null when
     * there was nothing to write or the write failed — the caller logs and reports that, exactly as
     * before.
     */
    public static Path write(Path dir, Header header, int known, List<ScannedContainer> containers,
                             List<ScanReport.FailureEntry> failures, List<ScanReport.FailureEntry> skipped,
                             Map<String, Integer> maxStackSizes) {
        if (containers.isEmpty() && failures.isEmpty() && skipped.isEmpty()) {
            HoardkeeperMod.LOGGER.warn("Nothing to export: {} holds no containers", dir);
            return null;
        }

        ScanReport report = ReportBuilder.build(header.sessionId(), header.server(), header.dimension(),
                header.origin(), header.area(), DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                known, containers, failures, skipped, maxStackSizes);

        Path out = dir.resolve("report.json");
        try {
            Files.writeString(out, PRETTY_GSON.toJson(report));
        } catch (IOException e) {
            HoardkeeperMod.LOGGER.error("Failed to write report to {}", out, e);
            return null;
        }
        HoardkeeperMod.LOGGER.info("Report written to {}: session={} dim={} known={} {} containers, "
                        + "{} failures, {} skipped, {} distinct items", out, header.sessionId(),
                header.dimension(), known, containers.size(), failures.size(), skipped.size(),
                report.totalsByItem.size());
        return out;
    }
}
