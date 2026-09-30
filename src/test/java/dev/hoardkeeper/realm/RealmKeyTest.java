package dev.hoardkeeper.realm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RealmKeyTest {

    @BeforeEach
    void clear() {
        RealmKey.get().onDisconnect();
        RealmKey.get().setListener(key -> { });
    }

    @Test
    void formatsTheSeedAsAPrefixedFixedWidthHexString() {
        // Fixed width and a provenance prefix: the server treats the whole string as opaque, and a
        // later plugin-supplied id lands in the same field as "plugin:<name>".
        assertEquals("seed:0000000000000001", RealmKey.format(1L));
        assertEquals("seed:ffffffffffffffff", RealmKey.format(-1L));
    }

    @Test
    void isUnknownUntilALoginPacketArrives() {
        // Not "singleplayer", not the connected address: an invented realm is worse than none,
        // because the server maps a key it does not recognise to a 400 and a wrong one to silent
        // cross-realm overwrites.
        assertNull(RealmKey.get().current());
    }

    @Test
    void takesTheSeedFromTheSpawnInfo() {
        RealmKey.get().onSpawnInfo(0x1d333b8d147874e9L);
        assertEquals("seed:1d333b8d147874e9", RealmKey.get().current());
    }

    @Test
    void theLatestSpawnInfoWins() {
        // A proxy backend switch delivers a fresh login packet on the same connection; the realm
        // must follow it rather than stay on the backend the session started on.
        RealmKey.get().onSpawnInfo(1L);
        RealmKey.get().onSpawnInfo(2L);
        assertEquals("seed:0000000000000002", RealmKey.get().current());
    }

    @Test
    void forgetsTheRealmOnDisconnect() {
        // Stale is worse than absent: the next scan must not be stamped with the realm of whatever
        // server this client happened to be on before.
        RealmKey.get().onSpawnInfo(1L);
        RealmKey.get().onDisconnect();
        assertNull(RealmKey.get().current());
    }

    @Test
    void tellsTheListenerWhenTheRealmChanges() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        RealmKey.get().setListener(seen::add);

        RealmKey.get().onSpawnInfo(1L);   // first key of the connection
        RealmKey.get().onSpawnInfo(2L);   // a proxy moved us to another backend

        assertEquals(java.util.List.of("seed:0000000000000002"), seen);
    }

    @Test
    void theFirstRealmOfAConnectionIsNotAChange() {
        // Joining is not "the realm changed under a running scan" -- there is no scan yet, and
        // firing here would interrupt the session the player is about to be offered a resume for.
        java.util.List<String> seen = new java.util.ArrayList<>();
        RealmKey.get().setListener(seen::add);

        RealmKey.get().onSpawnInfo(1L);

        assertEquals(java.util.List.of(), seen);
    }

    @Test
    void aRespawnInsideTheSameRealmIsNotAChange() {
        // Measured on Beacoland: a nether portal sends a respawn packet carrying the SAME hashed
        // seed. Treating that as a realm change would abort a scan every time somebody walks
        // through a portal.
        java.util.List<String> seen = new java.util.ArrayList<>();
        RealmKey.get().setListener(seen::add);

        RealmKey.get().onSpawnInfo(1L);
        RealmKey.get().onSpawnInfo(1L);

        assertEquals(java.util.List.of(), seen);
    }
}
