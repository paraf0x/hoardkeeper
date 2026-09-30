package dev.hoardkeeper.hud;

import dev.hoardkeeper.scan.ClusterHint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudTextModelTest {

    @Test
    void mapsEachOctantToItsArrow() {
        assertEquals("↑", HudTextModel.arrowGlyph(0));
        assertEquals("↗", HudTextModel.arrowGlyph(45));
        assertEquals("→", HudTextModel.arrowGlyph(90));
        assertEquals("↘", HudTextModel.arrowGlyph(135));
        assertEquals("↓", HudTextModel.arrowGlyph(180));
        assertEquals("↙", HudTextModel.arrowGlyph(-135));
        assertEquals("←", HudTextModel.arrowGlyph(-90));
        assertEquals("↖", HudTextModel.arrowGlyph(-45));
    }

    @Test
    void roundsToTheNearestOctantRatherThanTruncating() {
        assertEquals("↑", HudTextModel.arrowGlyph(20));
        assertEquals("↗", HudTextModel.arrowGlyph(30));
        assertEquals("↓", HudTextModel.arrowGlyph(-180));
    }

    @Test
    void normalisesYawIntoTheOpenClosedInterval() {
        double yaw = HudTextModel.relativeYaw(new double[]{0, 0, 0}, new double[]{0, 0, 10}, 0f);
        assertTrue(yaw > -180.0 && yaw <= 180.0);
    }

    @Test
    void formatsEtaAsMinutesAndSeconds() {
        assertEquals("1m 03s", HudTextModel.formatEta(315, 5.0));
        assertEquals("12s", HudTextModel.formatEta(60, 5.0));
    }

    @Test
    void showsADashWhenThereIsNoRateYet() {
        assertEquals("—", HudTextModel.formatEta(100, 0.0));
    }

    @Test
    void firstLineCarriesTheCountsAndFailuresAreOmittedWhenZero() {
        List<String> withFailures = HudTextModel.lines(127, 430, 3, null, 0, 4.8, 81, 81);
        List<String> clean = HudTextModel.lines(127, 430, 0, null, 0, 4.8, 81, 81);

        assertTrue(withFailures.get(0).contains("127"));
        assertTrue(withFailures.get(0).contains("430"));
        assertTrue(withFailures.get(0).contains("3"));
        assertTrue(clean.get(0).contains("127"));
        assertTrue(clean.stream().noneMatch(l -> l.contains("failed")));
    }

    @Test
    void includesTheClusterHintWhenThereIsOne() {
        ClusterHint.Cluster c = new ClusterHint.Cluster(new double[]{20, 64, 20}, 7, 23.4);

        List<String> lines = HudTextModel.lines(10, 100, 0, c, 45, 5.0, 81, 81);

        assertTrue(lines.stream().anyMatch(l -> l.contains("7") && l.contains("23")),
                "cluster line must name the count and the distance");
        assertTrue(lines.stream().anyMatch(l -> l.contains("↗")));
    }
}
