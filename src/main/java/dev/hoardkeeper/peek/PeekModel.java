package dev.hoardkeeper.peek;

import dev.hoardkeeper.index.ContainerView;
import dev.hoardkeeper.scan.ContainerKind;
import dev.hoardkeeper.search.SearchText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the card says while crouching in front of a container: design spec §6, the three states.
 *
 * <p>Pure — no Minecraft import, no colour codes — for the same reason {@code tooltip.TooltipText}
 * is: a card drawn every frame the player crouches must be nothing but a lookup and a string build
 * on the render path, and that is only testable without a client if this class never touches one.
 * Unlike {@code TooltipText}, this class returns <em>plain</em> strings with no {@code §} codes at
 * all — {@code peek.PeekRenderer} (a later task) draws the card as a HUD element, not chat, and
 * colour there is that renderer's decision to make, not a string this class bakes in.
 *
 * <p><b>Three states, plus a fourth that is not a card at all</b> (spec §6):
 * <ul>
 *   <li><b>Contents</b> — {@code view} is not {@code null}: kind/title and age, then item lines,
 *       then the slots line.</li>
 *   <li><b>Empty</b> — {@code view} is not {@code null} but holds no items: the single word
 *       {@code "empty"}, nothing else. A container the scan found genuinely empty does not need its
 *       age or its slot count repeated back — "empty" already says everything there is to know.</li>
 *   <li><b>Not scanned yet</b> — {@code view} is {@code null} but {@code kindAtPosition} is not:
 *       what a chest built after the scan looks like, and saying so plainly is the point of the
 *       feature (design spec, "Facts" section). The single line {@code "not scanned yet"}.</li>
 *   <li><b>No card at all</b> — both {@code view} and {@code kindAtPosition} are {@code null}: the
 *       player is looking at a wall, not a container anybody missed. An empty list, not a line that
 *       says so, because there is nothing here to have an opinion about.</li>
 * </ul>
 *
 * <p>{@code kindAtPosition} exists solely to tell "not scanned yet" apart from "no card at all" —
 * once {@code view} is non-null its own {@link ContainerView#kind()} is what the title line reads,
 * because that is the kind the scan actually recorded, not necessarily whatever the caller's live
 * block-state lookup says right now.
 */
public final class PeekModel {

    private static final String EMPTY = "empty";
    private static final String NOT_SCANNED_YET = "not scanned yet";
    private static final String ITEM_COLUMN_GAP = "  ";

    private PeekModel() {
    }

    /**
     * The lines to draw for the container at the position the caller already looked up, in order.
     *
     * @param view           the scan's record of this position, or {@code null} when nothing scanned
     *                       landed there
     * @param kindAtPosition what {@code ContainerDiscovery.kindOf} says the live block is, or
     *                       {@code null} when it is not a container at all — read only when
     *                       {@code view} is {@code null}, to tell the third state from the fourth
     * @param maxItems       item lines to show before folding the rest into {@code "+N more"};
     *                       values below 1 are treated as 0 without failing, since a config file
     *                       could in principle hand this a bad number and a card with zero item
     *                       lines and a correct {@code +N more} is still an honest answer
     * @param nowMillis      the instant to measure the container's age against
     */
    public static List<String> lines(ContainerView view, ContainerKind kindAtPosition, int maxItems,
                                      long nowMillis) {
        if (view == null) {
            return kindAtPosition == null ? List.of() : List.of(NOT_SCANNED_YET);
        }
        Map<String, Long> counts = view.counts();
        if (counts == null || counts.isEmpty()) {
            return List.of(EMPTY);
        }

        List<String> lines = new ArrayList<>();
        lines.add(titleLine(view, nowMillis));
        lines.addAll(itemLines(counts, maxItems));
        if (view.slotCount() > 0) {
            lines.add(view.usedSlots() + "/" + view.slotCount() + " slots");
        }
        return lines;
    }

    /**
     * "{@code <title or kind>} · seen {@code <age>}", or just the label when {@link RelativeTime}
     * has nothing honest to say about the age (spec §10: a missing or unparseable {@code scannedAt}
     * omits the age rather than inventing one).
     */
    private static String titleLine(ContainerView view, long nowMillis) {
        String label = hasText(view.title()) ? view.title() : displayKind(view.kind());
        String age = RelativeTime.since(view.scannedAt(), nowMillis);
        return age == null ? label : label + " · seen " + age;
    }

    /**
     * Sorted by count descending — the point of the card is "what does this container mostly hold",
     * and a player scanning it top to bottom should meet the biggest stack first — then by id, so
     * two items tied on count still print in the same order every time this is called with the same
     * data. Padding is computed over only the rows actually shown, exactly as
     * {@code site.SiteText}'s shortfall rows do, so raising or lowering {@code maxItems} cannot
     * shift a row that is still shown out of alignment with itself.
     */
    private static List<String> itemLines(Map<String, Long> counts, int maxItems) {
        List<Map.Entry<String, Long>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toList());

        int shown = Math.min(Math.max(0, maxItems), sorted.size());
        int nameWidth = 0;
        int countWidth = 0;
        for (int i = 0; i < shown; i++) {
            Map.Entry<String, Long> entry = sorted.get(i);
            nameWidth = Math.max(nameWidth, SearchText.shortId(entry.getKey()).length());
            countWidth = Math.max(countWidth, SearchText.count(entry.getValue()).length());
        }

        List<String> lines = new ArrayList<>(shown + 1);
        for (int i = 0; i < shown; i++) {
            Map.Entry<String, Long> entry = sorted.get(i);
            lines.add(padRight(SearchText.shortId(entry.getKey()), nameWidth) + ITEM_COLUMN_GAP
                    + padLeft(SearchText.count(entry.getValue()), countWidth));
        }
        if (sorted.size() > shown) {
            lines.add("+" + (sorted.size() - shown) + " more");
        }
        return lines;
    }

    /**
     * {@code "chest_double"} as a person reads it: {@code "Chest Double"}. There is no registry of
     * pretty container names to draw on without a Minecraft import, so this only ever has the
     * scan's own lowercase id to work with — splitting on {@code _} and capitalising each word is
     * what turns that id back into something that reads like a label rather than a file name.
     */
    private static String displayKind(String kindId) {
        if (!hasText(kindId)) {
            return "Container";
        }
        StringBuilder out = new StringBuilder();
        for (String word : kindId.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.length() == 0 ? "Container" : out.toString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String padRight(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
    }

    private static String padLeft(String text, int width) {
        return " ".repeat(Math.max(0, width - text.length())) + text;
    }
}
