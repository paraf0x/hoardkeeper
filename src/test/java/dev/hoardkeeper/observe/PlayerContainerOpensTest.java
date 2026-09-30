package dev.hoardkeeper.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerContainerOpensTest {

    @Test
    void confirmsAClickInsideTheWindow() {
        PlayerContainerOpens opens = new PlayerContainerOpens();
        opens.clicked(10, 64, -3, 100);
        assertArrayEquals(new int[]{10, 64, -3}, opens.confirm(139));
    }

    @Test
    void refusesAClickPastTheWindow() {
        PlayerContainerOpens opens = new PlayerContainerOpens();
        opens.clicked(10, 64, -3, 100);
        assertNull(opens.confirm(141));
    }

    @Test
    void aMenuWithNoClickIsNeverCredited() {
        // A plugin GUI the server opens on join has no right-click behind it.
        assertNull(new PlayerContainerOpens().confirm(5));
    }

    @Test
    void aClickIsConsumedOnce() {
        PlayerContainerOpens opens = new PlayerContainerOpens();
        opens.clicked(1, 2, 3, 10);
        assertNotNull(opens.confirm(12));
        assertNull(opens.confirm(13), "a second menu must not be credited to the same click");
    }

    @Test
    void expireDropsAStaleClick() {
        PlayerContainerOpens opens = new PlayerContainerOpens();
        opens.clicked(1, 2, 3, 10);
        opens.expire(100);
        assertNull(opens.confirm(101));
    }
}
