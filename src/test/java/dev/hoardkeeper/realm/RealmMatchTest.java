package dev.hoardkeeper.realm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmMatchTest {

    @Test
    void aSessionFromThisRealmMayBeResumed() {
        assertTrue(RealmMatch.resumable("seed:aaa", "seed:aaa"));
    }

    @Test
    void aSessionFromAnotherRealmMayNotBe() {
        // The whole point: after a proxy backend switch the dimension still matches, so without
        // this the resume offer hands you the realm you just left and the next scan writes its
        // containers into that session.
        assertFalse(RealmMatch.resumable("seed:kingdom", "seed:survival"));
    }

    @Test
    void aSessionWithoutARealmStaysResumable() {
        // Scanned before the realm field existed. Excluding these would quietly make every old
        // session unresumable, which is a bigger loss than the risk it avoids.
        assertTrue(RealmMatch.resumable("seed:aaa", null));
    }

    @Test
    void anUnknownCurrentRealmDoesNotExcludeAnything() {
        // Singleplayer, or a login packet not seen yet. "I don't know where I am" is not evidence
        // that this session belongs somewhere else.
        assertTrue(RealmMatch.resumable(null, "seed:aaa"));
        assertTrue(RealmMatch.resumable(null, null));
    }
}
