package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.scan.CandidateCounts;
import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.ContainerStatus;
import dev.hoardkeeper.scan.FailReason;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.scan.ScanSession;

import java.util.Arrays;

/**
 * A locked chest makes the server refuse the menu without a packet the client could foresee:
 * NO_RESPONSE, a remote reason, so retry-failed puts it back and it scans once unlocked.
 * A block on top would be BLOCKED, a local skip, and retry-failed would rightly ignore it.
 */
public final class RetryScenario implements Scenario {
    private static final String LOCK = " {lock:{items:\"minecraft:diamond\"}}";

    @Override
    public String name() {
        return "retry";
    }

    @Override
    public void run(Harness h) throws Exception {
        h.openServer();
        h.setConfig(c -> {
            c.minTicksBetweenOpens = 4;
            c.autoFinishSeconds = 60;        // the scan must still be running when retry-failed is sent
            c.maxAttemptsPerContainer = 1;   // one timeout is enough evidence
            c.openTimeoutTicks = 20;
        });
        String at = Fixtures.NEAREST_CHEST.getX() + " " + Fixtures.NEAREST_CHEST.getY() + " "
                + Fixtures.NEAREST_CHEST.getZ();
        h.serverCommand("data merge block " + at + LOCK);
        h.ctx().waitTicks(5);

        h.command("hoard start 8");
        h.waitFor("scan running", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        ScanSession session = h.onClient(mc -> ScanController.get().session());

        h.waitFor("one container failed", mc ->
                CandidateCounts.count(session.candidates, ContainerStatus.FAILED) == 1, 20 * Harness.SECOND);
        ContainerCandidate failed = h.onClient(mc -> session.candidates.stream()
                .filter(c -> c.status == ContainerStatus.FAILED).findFirst().orElseThrow());
        h.checkEquals("the failed container is the locked chest",
                Arrays.toString(new int[]{Fixtures.NEAREST_CHEST.getX(), Fixtures.NEAREST_CHEST.getY(),
                        Fixtures.NEAREST_CHEST.getZ()}), Arrays.toString(failed.pos));
        h.checkEquals("fail reason is remote", FailReason.NO_RESPONSE, failed.failReason);

        // Unlock and retry right away, before the rest of the base finishes: ScanController's
        // "nothing left to scan" check (ScanCompletion.nothingLeftToScan) fires on the very next
        // client tick once every other candidate is SCANNED and every chunk in radius is already
        // loaded (true from the moment openServer() finishes) -- it is not gated by
        // autoFinishSeconds, which only paces the *idle-timeout* finish, not this one. Waiting here
        // for "everything else scanned" first (the brief's original order) loses the race: by the
        // time that count is observably true the scan has already stopped, retry-failed's requeue
        // sits PENDING forever (onClientTick returns immediately once !running), and "scanned after
        // retry" times out. See task-6-report.md for the measured timings that pinned this down.
        h.check("scan still running before retry", h.onClient(mc -> ScanController.get().isRunning()),
                "isRunning");
        h.serverCommand("data remove block " + at + " lock");
        h.ctx().waitTicks(5);
        h.command("hoard retry-failed");
        h.waitFor("the unlocked chest scanned after retry", mc ->
                CandidateCounts.count(session.candidates, ContainerStatus.SCANNED) == Fixtures.CONTAINERS,
                20 * Harness.SECOND);
        h.checkEquals("scanned after retry", Fixtures.CONTAINERS,
                h.onClient(mc -> CandidateCounts.count(session.candidates, ContainerStatus.SCANNED)));
        h.checkEquals("failed after retry", 0,
                h.onClient(mc -> CandidateCounts.count(session.candidates, ContainerStatus.FAILED)));

        h.command("hoard stop");
        h.waitForScanEnd();

        retryAfterTheScanEnded(h, at);
    }

    /**
     * The second half: what happens when the player types retry-failed once the scan is over.
     *
     * <p>It used to re-queue the failed containers and say so — and then nothing happened ever
     * again, because {@code onClientTick} returns immediately while {@code running} is false and
     * {@code stop()} has already closed the writers. The candidates lost their fail reason on the
     * way, so even {@code status} could no longer say what had gone wrong.
     */
    private void retryAfterTheScanEnded(Harness h, String at) {
        h.serverCommand("data merge block " + at + LOCK);
        h.ctx().waitTicks(5);

        h.command("hoard start 8");
        h.waitFor("second scan running", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        ScanSession second = h.onClient(mc -> ScanController.get().session());
        h.waitFor("second scan finished by itself, one container failed", mc ->
                        !ScanController.get().isRunning()
                                && CandidateCounts.count(second.candidates, ContainerStatus.FAILED) == 1,
                40 * Harness.SECOND);

        h.command("hoard retry-failed");
        boolean stillFailed = h.stays(mc ->
                CandidateCounts.count(second.candidates, ContainerStatus.FAILED) != 1,
                3 * Harness.SECOND);
        h.check("retry-failed after the scan ended leaves the failure alone", stillFailed,
                "failed=" + h.onClient(mc -> CandidateCounts.count(second.candidates, ContainerStatus.FAILED))
                        + " pending=" + h.onClient(mc ->
                        CandidateCounts.count(second.candidates, ContainerStatus.PENDING)));
        h.checkEquals("nothing was queued that nothing would send", 0,
                h.onClient(mc -> CandidateCounts.count(second.candidates, ContainerStatus.PENDING)));
        ContainerCandidate stillLocked = h.onClient(mc -> second.candidates.stream()
                .filter(c -> c.status == ContainerStatus.FAILED).findFirst().orElse(null));
        h.checkEquals("the fail reason survives the refusal", FailReason.NO_RESPONSE,
                stillLocked == null ? null : stillLocked.failReason);

        h.serverCommand("data remove block " + at + " lock");
    }
}
