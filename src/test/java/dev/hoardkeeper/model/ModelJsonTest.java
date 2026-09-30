package dev.hoardkeeper.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelJsonTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void omitsNullFieldsSoTheBulkFileStaysSmall() {
        ScannedItem item = new ScannedItem();
        item.slot = 0;
        item.id = "minecraft:iron_ingot";
        item.count = 64;

        String json = GSON.toJson(item);

        assertTrue(json.contains("\"id\""));
        assertFalse(json.contains("\"name\""), "null custom name must not be serialised");
        assertFalse(json.contains("\"contents\""), "null nesting must not be serialised");
    }

    @Test
    void roundTripsANestedContainer() {
        ScannedItem inner = new ScannedItem();
        inner.id = "minecraft:raw_iron";
        inner.count = 64;

        ScannedItem shulker = new ScannedItem();
        shulker.slot = 9;
        shulker.id = "minecraft:red_shulker_box";
        shulker.count = 1;
        shulker.name = "Erz";
        shulker.contents = List.of(inner);

        ScannedContainer c = new ScannedContainer();
        c.pos = new int[]{1, 2, 3};
        c.kind = "chest_double";
        c.slotCount = 54;
        c.usedSlots = 1;
        c.items = List.of(shulker);
        c.totals = Map.of("minecraft:raw_iron", 64L);

        ScannedContainer back = GSON.fromJson(GSON.toJson(c), ScannedContainer.class);

        assertEquals(54, back.slotCount);
        assertEquals("Erz", back.items.get(0).name);
        assertEquals("minecraft:raw_iron", back.items.get(0).contents.get(0).id);
        assertEquals(64L, back.totals.get("minecraft:raw_iron"));
        assertNull(back.secondaryPos);
    }

    @Test
    void sessionSnapshotRoundTripsCandidateStates() {
        CandidateState cs = new CandidateState();
        cs.pos = new int[]{10, 64, -20};
        cs.kind = "barrel";
        cs.status = "FAILED";
        cs.attempts = 3;
        cs.failReason = "NO_RESPONSE";

        SessionSnapshot s = new SessionSnapshot();
        s.sessionId = "2026-09-02_14-31-07";
        s.state = "RUNNING";
        s.containers = List.of(cs);
        s.stats = new SessionSnapshot.Stats();
        s.stats.known = 1;

        SessionSnapshot back = GSON.fromJson(GSON.toJson(s), SessionSnapshot.class);

        assertEquals(3, back.schemaVersion, "schema 3 added areaMode and chunkRadius");
        assertNull(back.uploadedAt, "a session is not uploaded until an upload says so");
        assertNull(back.areaMode, "and an unset mode is what an old snapshot looks like");
        assertEquals("NO_RESPONSE", back.containers.get(0).failReason);
        assertEquals(1, back.stats.known);
    }
}
