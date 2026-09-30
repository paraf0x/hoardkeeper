package dev.hoardkeeper.store;

import dev.hoardkeeper.capture.Totals;
import dev.hoardkeeper.model.ScanReport;
import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.scan.ScanArea;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates a finished scan's containers, failures and local skips into a single
 * {@link ScanReport}.
 *
 * <p>{@link #build} takes {@code maxStackSizes} as a plain parameter rather than reading it from
 * Minecraft's item registry. That is exactly what keeps this class pure and unit-testable without
 * a running game; ids missing from the map are treated as stacking to 64.
 */
public final class ReportBuilder {

    private ReportBuilder() {
    }

    /**
     * @param known    every container the scan discovered, whatever became of it. Passed in rather
     *                 than derived, because {@code scanned + failed + skipped} misses the
     *                 candidates a scan that was stopped early left still {@code PENDING} — and
     *                 hiding those is exactly the dishonesty this parameter exists to prevent.
     * @param skipped  containers deliberately not opened ({@code BLOCKED}, {@code GONE}). Kept
     *                 separate from {@code failures} — they are not the server refusing us — but
     *                 reported, because a container that exists and was never looked inside is
     *                 something the reader has to be told about.
     */
    public static ScanReport build(String sessionId, String server, String dimension, int[] origin,
                                    ScanArea area, String generatedAt, int known,
                                    List<ScannedContainer> containers,
                                    List<ScanReport.FailureEntry> failures,
                                    List<ScanReport.FailureEntry> skipped,
                                    Map<String, Integer> maxStackSizes) {
        ScanReport report = new ScanReport();
        report.sessionId = sessionId;
        report.server = server;
        report.dimension = dimension;
        report.origin = origin;
        report.radius = area.equivalentBlockRadius();
        report.areaMode = area.mode().name();
        report.chunkRadius = area.chunkRadius();
        report.generatedAt = generatedAt;
        report.failed = failures;
        report.skipped = skipped;

        ScanReport.Counts counts = new ScanReport.Counts();
        counts.known = known;
        counts.scanned = containers.size();
        counts.failed = failures.size();
        counts.skipped = skipped.size();
        counts.byKind = new LinkedHashMap<>();
        for (ScannedContainer container : containers) {
            counts.byKind.merge(container.kind, 1, Integer::sum);
        }
        report.containers = counts;

        Map<String, Long> totals = new LinkedHashMap<>();
        for (ScannedContainer container : containers) {
            Totals.of(container.items).forEach((id, count) -> totals.merge(id, count, Long::sum));
        }
        report.totals = totals;

        List<ScanReport.ItemTotal> totalsByItem = new ArrayList<>();
        for (Map.Entry<String, Long> entry : totals.entrySet()) {
            ScanReport.ItemTotal itemTotal = new ScanReport.ItemTotal();
            itemTotal.id = entry.getKey();
            itemTotal.count = entry.getValue();
            int maxStackSize = maxStackSizes.getOrDefault(entry.getKey(), 64);
            itemTotal.stacks = entry.getValue() / (double) maxStackSize;
            itemTotal.doubleChests = itemTotal.stacks / 54.0;
            totalsByItem.add(itemTotal);
        }
        // Big piles first: sort by count descending, then id ascending for a stable, deterministic order.
        totalsByItem.sort(Comparator.<ScanReport.ItemTotal>comparingLong(it -> it.count).reversed()
                .thenComparing(it -> it.id));
        report.totalsByItem = totalsByItem;

        return report;
    }
}
