package dev.hoardkeeper.hud;

import dev.hoardkeeper.scan.ClusterHint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the HUD's {@code §}-coloured status lines from plain scan numbers. Takes no Minecraft
 * types: positions are {@code double[]}, yaw is a plain {@code float}/{@code double} — so this can
 * be exercised without a running game. Everything it renders is English, matching the rest of the
 * mod.
 */
public final class HudTextModel {

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private HudTextModel() {
    }

    /** One of the eight compass arrows, rounded to the nearest 45° octant (not truncated). */
    public static String arrowGlyph(double relativeYawDegrees) {
        int octant = (int) Math.round(relativeYawDegrees / 45.0);
        return ARROWS[Math.floorMod(octant, 8)];
    }

    /**
     * Yaw from the eye toward the target, relative to the player's own yaw, normalised into
     * {@code (-180, 180]}. Minecraft's yaw convention measures from {@code atan2(dx, dz)}
     * (x before z) — note the argument order.
     */
    public static double relativeYaw(double[] eye, double[] target, float playerYaw) {
        double dx = target[0] - eye[0];
        double dz = target[2] - eye[2];
        double worldYaw = Math.toDegrees(Math.atan2(dx, dz));
        double relative = worldYaw - playerYaw;

        double normalised = ((relative + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        if (normalised <= -180.0) {
            normalised += 360.0;
        }
        return normalised;
    }

    /** {@code "—"} when there is no rate yet, else {@code "1m 03s"} / {@code "12s"}. */
    public static String formatEta(int remaining, double perSecond) {
        if (perSecond <= 0) {
            return "—";
        }
        int seconds = (int) Math.round(remaining / perSecond);
        if (seconds < 60) {
            return seconds + "s";
        }
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return minutes + "m " + String.format(Locale.ROOT, "%02d", secs) + "s";
    }

    /** The HUD's lines of text, in display order. */
    public static List<String> lines(int scanned, int known, int failed, ClusterHint.Cluster cluster,
                                      double relativeYaw, double perSecond, int chunksLoaded, int chunksInRadius) {
        List<String> lines = new ArrayList<>();

        StringBuilder first = new StringBuilder("§eStorage Scan  §f")
                .append(scanned).append("§7/§f").append(known);
        if (failed > 0) {
            first.append(" §7(§c").append(failed).append(" failed§7)");
        }
        lines.add(first.toString());

        if (cluster != null) {
            lines.add("§7nearest cluster §f" + Math.round(cluster.distance()) + "m §e"
                    + arrowGlyph(relativeYaw) + " §7(" + cluster.count() + " containers)");
        }

        int remaining = Math.max(0, known - scanned);
        String rate = String.format(Locale.ROOT, "%.1f", perSecond);
        lines.add("§7Rate " + rate + "/s   ETA " + formatEta(remaining, perSecond)
                + "   Chunks " + chunksLoaded + "/" + chunksInRadius);

        return lines;
    }
}
