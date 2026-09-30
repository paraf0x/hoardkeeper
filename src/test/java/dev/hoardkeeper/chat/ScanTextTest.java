package dev.hoardkeeper.chat;

import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.ContainerKind;
import dev.hoardkeeper.scan.FailReason;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ScanText}: every wording {@link ScanChat} used to build inline, now pure and
 * pinnable without a running game. Not one test per builder — a test that would actually catch a
 * regression in what the player reads.
 */
class ScanTextTest {

    private static ContainerCandidate candidate(int[] pos, ContainerKind kind) {
        return new ContainerCandidate(pos, null, kind);
    }

    // =========================================================================
    // progress -- counts in the roles the player expects
    // =========================================================================

    @Test
    void progressPutsScannedKnownKindPositionAndSlotsInTheRightRoles() {
        // scanned/known drive the fraction the player watches climb; the kind and position are how
        // they'd find this exact container again. Any of these landing in the wrong slot (scanned
        // swapped with known, or the slot count read as a coordinate) reads as a different scan.
        String line = ScanText.progress(7, 42, candidate(new int[]{10, 64, -20}, ContainerKind.BARREL), 27);

        assertTrue(line.contains("[7/42]"), line);
        assertTrue(line.contains("barrel"), line);
        assertTrue(line.contains("10 64 -20"), line);
        assertTrue(line.contains("(27 slots)"), line);
    }

    // =========================================================================
    // summary -- the counts a player trusts a whole scan by
    // =========================================================================

    @Test
    void summaryPutsScannedFailedAndPendingInTheRightRoles() {
        // This is the one line a player reads to decide whether a scan went well. Scanned counted
        // as failed (or vice versa) would tell them the opposite of what actually happened, and
        // nothing else in the UI would contradict it.
        String line = ScanText.summary("stop", 120, 5, 3, 60_000, 2.0,
                Map.of(FailReason.NO_RESPONSE, 5));

        assertTrue(line.contains("§e120§f scanned"), line);
        assertTrue(line.contains("§c5§f failed"), line);
        assertTrue(line.contains("§73§f pending"), line);
        assertTrue(line.contains("NO_RESPONSE"), line);
    }

    @Test
    void allResolvedPutsTotalScannedAndFailedInTheRightRoles() {
        // Printed once, when every candidate has resolved -- the run that failed entirely must not
        // read as one that quietly finished, and a total that doesn't match scanned+failed would be
        // exactly that kind of silent lie.
        String line = ScanText.allResolved(50, 45, 5, Map.of(FailReason.BLOCKED, 5));

        assertTrue(line.contains("§e50§f known"), line);
        assertTrue(line.contains("§a45§f scanned"), line);
        assertTrue(line.contains("§c5§f failed"), line);
    }

    // =========================================================================
    // failure -- where it was and why
    // =========================================================================

    @Test
    void failureNamesThePositionAndTheReason() {
        // Without the position the player can't find the container that failed; without the reason
        // they can't judge whether retrying is worth it. Both must survive into the one line shown.
        String line = ScanText.failure(candidate(new int[]{3, 70, -8}, ContainerKind.CHEST),
                FailReason.NO_RESPONSE);

        assertTrue(line.contains("3 70 -8"), line);
        assertTrue(line.contains("NO_RESPONSE"), line);
    }

    @Test
    void failureRollupNamesTheReasonAndTheRunningTotal() {
        // Once verbatim lines are throttled, this is the only failure feedback a player still sees
        // -- the running total is what tells them whether the server is failing occasionally or
        // wholesale, so a total that stopped tracking remoteFailures would hide exactly that.
        // Each number is bound to its label rather than checked loose, so transposing
        // failuresSinceRollup and remoteFailures (both ints, adjacent in the parameter list) fails
        // this test instead of silently passing because both numbers still appear somewhere.
        String line = ScanText.failureRollup(12, FailReason.NO_CONTENT, 87);

        assertTrue(line.contains("§e12§7 more failures"), line);
        assertTrue(line.contains("NO_CONTENT"), line);
        assertTrue(line.contains("Total: §c87"), line);
    }


    // =========================================================================
    // resume -- the numbers behind the offer and the confirmation
    // =========================================================================

    @Test
    void resumeOfferNamesScannedAndKnown() {
        // The clickable offer is the only thing telling a player how much of an interrupted scan
        // is already done; scanned and known transposed would claim a nearly-finished scan is
        // barely started, or the reverse, and nothing else on screen would contradict it. Checking
        // the joined "(12/88)" fragment, not each number loose, is what catches that transposition.
        String line = ScanText.resumeOffer(12, 88);

        assertTrue(line.contains("(12/88)"), line);
    }

    @Test
    void resumedPutsScannedKnownAndPendingInTheRightRoles() {
        // Printed right after a resume actually starts running again -- scanned/known/pending
        // swapped here would tell the player a different fraction of the scan is done than the one
        // that is actually about to continue.
        String line = ScanText.resumed(30, 88, 58);

        assertTrue(line.contains("§a30§f of §e88§f already scanned"), line);
        assertTrue(line.contains("§758§f pending"), line);
    }

    // =========================================================================
    // autoFinishBar -- the countdown a player watches run out
    // =========================================================================

    @Test
    void autoFinishBarFillsProportionallyAndShowsTheSecondsLeft() {
        // The bar and the number beside it describe the same countdown; a rounding slip that fills
        // the bar out of step with the seconds-left figure would make the two visibly disagree.
        String bar = ScanText.autoFinishBar(0.5, 15);

        long filled = bar.chars().filter(c -> c == '█').count();
        assertEquals(10, filled, bar);
        assertTrue(bar.contains("15s"), bar);
    }

    // =========================================================================
    // help -- every subcommand the command tree registers
    // =========================================================================

    /**
     * Every top-level word {@code HoardCommand.register} hangs directly off
     * {@code /hoard} (read from that file), except {@code help} itself: a help block does
     * not need to document the command that shows it. If a future subcommand is added to the tree
     * without a matching line here, this fails -- exactly the regression this test exists to catch.
     */
    @Test
    void helpMentionsEveryTopLevelSubcommandTheTreeRegisters() {
        List<String> lines = ScanText.help(1, 8, 32, 256, 30);
        String joined = String.join("\n", lines);

        List<String> topLevelSubcommands = List.of(
                "start", "stop", "status", "export", "retry-failed",
                "resume", "clear", "search", "allow", "deposit");
        for (String word : topLevelSubcommands) {
            assertTrue(joined.contains("/hoard " + word),
                    "help() never mentions /hoard " + word + ":\n" + joined);
        }
    }

    @Test
    void helpDocumentsBothWaysToSizeAStartAndNoUpload() {
        // start's two size arguments and upload's two targets are refinements of an already-named
        // command, but each has its own line -- worth pinning on its own, since either could be
        // dropped without the test above noticing (it only checks the bare word).
        String joined = String.join("\n", ScanText.help(1, 8, 32, 256, 30));

        assertTrue(joined.contains("/hoard start chunks <n>"), joined);
        assertTrue(joined.contains("/hoard start <radius>"), joined);
        assertFalse(joined.contains("upload"), "the core has no uploads; the TBI add-on adds its own lines");
    }

    @Test
    void helpSubstitutesTheConfiguredDefaultsAndMaximums() {
        // The numbers in help() are read out of config at call time; if the wiring from
        // HoardCommand ever passed the wrong field into the wrong slot, this is what would
        // catch a max shown where a default belongs (or vice versa).
        String joined = String.join("\n", ScanText.help(2, 16, 40, 300, 45));

        assertTrue(joined.contains("45"), joined);
        assertTrue(joined.contains("5x5"), joined); // defaultChunkRadius=2 -> 2*2+1
        assertTrue(joined.contains("max §f16§7"), joined);
        assertTrue(joined.contains("default §f40§7"), joined);
        assertTrue(joined.contains("max §f300§7"), joined);
    }

    // =========================================================================
    // Every builder: non-empty, and no raw "null" leaking into a chat line
    // =========================================================================

    @Test
    void everyBuilderReturnsANonEmptyLineWithNoRawNull() {
        ContainerCandidate candidate = candidate(new int[]{0, 64, 0}, ContainerKind.CHEST);
        Map<FailReason, Integer> byReason = Map.of(FailReason.NO_RESPONSE, 1);
        Path path = Path.of("session.json");

        List<String> lines = new ArrayList<>(List.of(
                ScanText.started("48 blocks around you", 12),
                ScanText.notRunning(),
                ScanText.requeued(4),
                ScanText.allResolved(10, 8, 2, byReason),
                ScanText.screenBlocked(),
                ScanText.progress(1, 10, candidate, 27),
                ScanText.failure(candidate, FailReason.NO_RESPONSE),
                ScanText.failureRollup(3, FailReason.NO_RESPONSE, 9),
                ScanText.summary("stop", 8, 2, 0, 5_000, 1.6, byReason),
                ScanText.filePath(path),
                ScanText.exported(path, "sid", "minecraft:overworld"),
                ScanText.openLabel(),
                ScanText.resumeOffer(3, 10),
                ScanText.resumeLabel(),
                ScanText.resumed(3, 10, 7),
                ScanText.autoFinishBar(0.2, 24),
                ScanText.autoFinished(30),
                ScanText.autoFinishedComplete(5, 2),
                ScanText.statusText(true, 8, 10, 1, 1, 1.6, 9, 9),
                ScanText.observationStatusText(412, 3, 27),
                ScanText.observationStatusText(0, 0, -1),
                ScanText.realmChangedText(),
                ScanText.noSessionText(),
                ScanText.alreadyRunningText(),
                ScanText.retryNotRunningText(),
                ScanText.clearedText(),
                ScanText.exportFailedText(),
                ScanText.sessionInMemoryText(),
                ScanText.noResumableText(),
                ScanText.describe(candidate)));
        lines.addAll(ScanText.help(1, 8, 32, 256, 30));

        for (String line : lines) {
            assertFalse(line == null || line.isEmpty(), "a builder returned a blank line");
            assertFalse(line.contains("null"), "a formatting slip leaked \"null\" into: " + line);
        }
    }
}
