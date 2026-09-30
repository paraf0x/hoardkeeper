package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.scan.CandidateCounts;
import dev.hoardkeeper.scan.ContainerStatus;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.scan.ScanSession;

/**
 * A relog mid-scan. DISCONNECT flushes and marks the snapshot INTERRUPTED; JOIN offers a resume;
 * /hoard resume calls the same resume(mc) the chat click calls, and the scan finishes
 * under the session id it started with.
 */
public final class ResumeScenario implements Scenario {
    @Override
    public String name() {
        return "resume";
    }

    @Override
    public void run(Harness h) throws Exception {
        h.openServer();
        h.setConfig(c -> {
            c.minTicksBetweenOpens = 20;     // a second per container, so there is a middle to interrupt
            c.autoFinishSeconds = 30;
        });

        h.command("hoard start 8");
        h.waitFor("scan running", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        ScanSession first = h.onClient(mc -> ScanController.get().session());
        h.waitFor("three scanned", mc ->
                CandidateCounts.count(first.candidates, ContainerStatus.SCANNED) >= 3, 20 * Harness.SECOND);
        int beforeDisconnect = h.onClient(mc -> CandidateCounts.count(first.candidates, ContainerStatus.SCANNED));

        h.disconnect();
        h.check("scan stopped on disconnect", !h.onClient(mc -> ScanController.get().isRunning()), "isRunning");
        h.reconnect();

        // The offer is a chat message and not observable; its precondition is.
        h.waitFor("idle with no session in memory (the resume offer's precondition)", mc ->
                mc.level != null && !ScanController.get().isRunning() && ScanController.get().session() == null,
                10 * Harness.SECOND);
        h.ctx().waitTicks(20);   // offerResume is deferred by one tick after JOIN; leave it room
        h.command("hoard resume");
        h.waitFor("scan resumed", mc -> ScanController.get().isRunning(), 5 * Harness.SECOND);
        ScanSession second = h.onClient(mc -> ScanController.get().session());
        h.checkEquals("same session id after resume", first.sessionId, second.sessionId);

        h.waitFor("scan end after resume", mc -> !ScanController.get().isRunning(), 120 * Harness.SECOND);
        int scanned = h.onClient(mc -> CandidateCounts.count(second.candidates, ContainerStatus.SCANNED));
        h.checkEquals("scanned after resume", Fixtures.CONTAINERS, scanned);
        h.number("scannedBeforeDisconnect", beforeDisconnect);
        h.number("scannedAfterResume", scanned);
    }
}
