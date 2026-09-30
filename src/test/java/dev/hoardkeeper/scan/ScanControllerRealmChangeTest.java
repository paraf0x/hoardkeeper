package dev.hoardkeeper.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for fix round 1, finding I-2: a proxy backend switch (no disconnect, no fresh join —
 * see {@code RealmKey.onSpawnInfo}) must clear the rescan nudge's connection-scoped state exactly as
 * a real disconnect does. Without this, {@code nudgedClusterIds}/{@code offerResumePrinted}/
 * {@code previousNudgeClusterId} from the old realm would survive onto the new one, and because
 * {@link RescanNudge#clusterId} is pure geometry with no realm component, a coincidental match
 * between two realms' spawn-area storages would read a genuinely new storage as "already nudged" —
 * permanently, for the rest of the connection.
 *
 * <p><b>Why this is reachable without a live Minecraft client, unlike the rest of this class.</b>
 * {@code onRealmChanged} clears the nudge state <em>before</em> its {@code session == null} guard.
 * With no session loaded — the exact state {@code checkRescanNudge} is active in (clause 4) — the
 * method never touches {@code Minecraft.getInstance()}, {@code saveState}, {@code reset()} or chat,
 * so calling it on the singleton with no session in memory exercises exactly the state transition
 * this finding is about and nothing that needs a game. The three fields under test were widened from
 * {@code private} to package-private for exactly this seam — see their own javadocs on
 * {@code ScanController}.
 */
class ScanControllerRealmChangeTest {

    @Test
    void aRealmSwitchWithNoSessionLoadedClearsTheNudgeState() {
        ScanController controller = ScanController.get();
        // ScanController is a process-wide singleton; start from a state this test controls rather
        // than assuming nothing else has touched it, so the assertions below are about what
        // onRealmChanged does, not about incidental leftover state.
        controller.nudgedClusterIds.clear();
        controller.nudgedClusterIds.add("hallA");
        controller.offerResumePrinted = true;
        controller.previousNudgeClusterId = "hallA";

        controller.onRealmChanged("some-other-realm");

        assertTrue(controller.nudgedClusterIds.isEmpty(),
                "already-nudged clusters from the old realm must not survive a realm switch");
        assertFalse(controller.offerResumePrinted,
                "offerResumePrinted from the old realm's join must not survive a realm switch");
        assertNull(controller.previousNudgeClusterId,
                "the old realm's cluster id must not be compared against the new realm's clusters");
    }
}
