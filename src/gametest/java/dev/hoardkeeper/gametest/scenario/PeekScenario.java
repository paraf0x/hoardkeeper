package dev.hoardkeeper.gametest.scenario;

import dev.hoardkeeper.gametest.Fixtures;
import dev.hoardkeeper.gametest.Harness;
import dev.hoardkeeper.gametest.Scenario;
import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.peek.PeekRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.List;

/**
 * The container card drawn while crouching in front of a chest, barrel or shulker box --
 * {@code peek.PeekRenderer}, commit 15047fd. Nobody has seen a frame of it before this scenario:
 * it walks all four states {@code peek.PeekModel} decides between, screenshotting the first three,
 * then proves the card does not outlive its condition (standing up mid-look clears it), and ends
 * with a best-effort screenshot of the tooltip line the design pins separately.
 *
 * <p><b>The "empty" state reuses {@link Fixtures#NEAREST_CHEST}</b> rather than a second fixture.
 * The design note this task was written from says the measured map "is rewritten by an observation
 * only if you open it" -- and {@link PassiveScenario} already proves exactly that sequence
 * (a {@code data merge block ... {Items:[]}} on a chest the scan recorded, then
 * {@link Harness#openAndClose}) turns into a projected, empty row on the map {@code StorageIndexCache}
 * -- and so this card -- reads. Reusing the Contents check's own chest for that, instead of spending
 * a fresh fixture on it, is simpler and reads as what a player would actually do: watch one chest go
 * from full to empty. {@link Fixtures#INTRUDER} is left alone for the "not scanned yet" state, which
 * needs a container the scan never touched at all.
 *
 * <p>Crouch is held for the whole walk through states 1-4 with {@code TestInput.holdKey} on the
 * sneak mapping, {@code Options.keyShift}, and released exactly once, for state 5, inside a
 * {@code finally} so a timeout earlier in the method cannot leave the crouch key down for whatever
 * scenario a chained run queues next. It is not, however, one single unbroken hold: opening a
 * screen resets Minecraft's own tracked sneak state the same way it resets every key (so a
 * leftover WASD from before a menu opened does not carry into it), which the empty-chest check's
 * {@link Harness#openAndClose} does mid-walk -- found by running this scenario and reading its own
 * diagnostic checks, not documented anywhere obvious. {@link #reassertCrouch} is the release-then-
 * hold that undoes that reset; a second {@code holdKey} alone is a no-op, since the test input's own
 * bookkeeping still believes the key from the very first {@code holdKey} call is down.
 */
public final class PeekScenario implements Scenario {

    /**
     * Three blocks straight up from the player -- clear of the player's own head (eye height is
     * about 1.6 blocks) and, more to the point, clear of every sightline this scenario ever aims
     * along ({@link Fixtures#NEAREST_CHEST} to the north, {@link Fixtures#INTRUDER} to the south).
     * An earlier version of this scenario put this block one step north, at (0,-60,1), reasoning
     * only about which ground columns the base layout leaves free (see {@link Fixtures}'s class
     * javadoc) and not about what else the player would later look past it to reach -- state 5's
     * look back at the chest hit that stone block instead, since it sat on the straight line
     * between the player and the chest. Straight up has no such line to sit on.
     */
    private static final BlockPos PLAIN_BLOCK = new BlockPos(0, -57, 0);

    @Override
    public String name() {
        return "peek";
    }

    @Override
    public void run(Harness h) throws Exception {
        SearchScenario.scanTheBase(h);
        h.setConfig(c -> {
            c.peekEnabled = true;
            c.passiveObserve = true;
        });

        h.ctx().getInput().holdKey(options -> options.keyShift);
        boolean released = false;
        try {
            // ---- 1. Contents: the scan's own record of a chest full of diamonds. ----
            lookAtAndSettle(h, Fixtures.NEAREST_CHEST);
            h.waitFor("contents card", mc -> !lines(mc).isEmpty(), 5 * Harness.SECOND);
            List<String> contents = h.onClient(PeekScenario::lines);
            h.check("contents card names the kind", !contents.isEmpty() && contents.get(0).contains("Chest"),
                    contents);
            h.check("contents card lists the diamonds", contents.stream()
                    .anyMatch(line -> line.contains("diamond") && line.contains("5")), contents);
            h.check("contents card ends with the slots line", !contents.isEmpty()
                    && contents.get(contents.size() - 1).endsWith("/27 slots"), contents);
            h.screenshot("contents");

            // ---- 2. Empty: the very same chest, emptied and reopened by hand. ----
            BlockPos chest = Fixtures.NEAREST_CHEST;
            h.serverCommand("data merge block " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
                    + " {Items:[]}");
            h.ctx().waitTicks(5);
            h.openAndClose(chest);
            reassertCrouch(h);
            MeasuredMapStore.awaitPendingWritesForTests();
            lookAtAndSettle(h, chest);
            h.waitFor("empty card", mc -> lines(mc).equals(List.of("empty")), 10 * Harness.SECOND);
            h.checkEquals("empty state after emptying and reopening the scanned chest",
                    List.of("empty"), h.onClient(PeekScenario::lines));
            h.screenshot("empty");

            // ---- 3. Not scanned yet: a chest the scan never knew about, inside the scanned area. ----
            BlockPos intruder = Fixtures.INTRUDER;
            h.serverCommand("setblock " + intruder.getX() + " " + intruder.getY() + " " + intruder.getZ()
                    + " minecraft:chest[facing=north,type=single]");
            h.ctx().waitTicks(5);
            lookAtAndSettle(h, intruder);
            h.waitFor("not-scanned-yet card", mc -> lines(mc).equals(List.of("not scanned yet")),
                    5 * Harness.SECOND);
            h.checkEquals("not scanned yet for a chest the scan never saw",
                    List.of("not scanned yet"), h.onClient(PeekScenario::lines));
            h.screenshot("not-scanned");

            // ---- 4. Nothing: a plain block is not a container to have an opinion about. ----
            h.serverCommand("setblock " + PLAIN_BLOCK.getX() + " " + PLAIN_BLOCK.getY() + " "
                    + PLAIN_BLOCK.getZ() + " minecraft:stone");
            h.ctx().waitTicks(5);
            lookAtAndSettle(h, PLAIN_BLOCK);
            h.ctx().waitTicks(2);
            List<String> plain = h.onClient(PeekScenario::lines);
            h.checkEquals("no card at all for a plain block", List.of(), plain);
            boolean staysEmpty = h.stays(mc -> !lines(mc).isEmpty(), 2 * Harness.SECOND);
            h.check("the absence of a card holds rather than flashing on", staysEmpty,
                    h.onClient(PeekScenario::lines));

            // ---- 5. Standing up: releasing crouch must clear whatever card is showing. ----
            lookAtAndSettle(h, chest);
            h.waitFor("a card is showing again before standing up", mc -> !lines(mc).isEmpty(),
                    5 * Harness.SECOND);
            List<String> beforeStandingUp = h.onClient(PeekScenario::lines);
            h.check("a card is visible right before standing up", !beforeStandingUp.isEmpty(), beforeStandingUp);

            // ---- 6. /hoard peek off/on: the command actually takes the card away and
            // brings it back, without moving or standing up. The scenario must leave the flag on
            // -- the config file it writes lives in build/run/clientGameTest and a chained run
            // would otherwise inherit "off", making every later peek check pass vacuously (I-3).
            // Wrapped in its own try/finally, beside the crouch key's, because a check or a wait
            // failing between "off" and "on" is exactly the failure this scenario must not leave
            // behind: GameTestReset's config reload only restores what ConfigGuard wrote before
            // this JVM's first scenario, and a runtime toggle persisted mid-run survives that
            // reload untouched. Harness.applyQuietOptions re-asserts peekEnabled too, as a
            // backstop for whatever gets past this -- but this scenario owns the flag it flips,
            // so it restores it itself first.
            boolean peekLeftOn = false;
            try {
                h.command("hoard peek off");
                h.ctx().waitTicks(3);
                h.checkEquals("peek off hides the card while still crouching at the chest",
                        List.of(), h.onClient(PeekScenario::lines));

                h.command("hoard peek on");
                h.waitFor("card back after peek on", mc -> !lines(mc).isEmpty(), 5 * Harness.SECOND);
                h.check("peek on brings the card back", !h.onClient(PeekScenario::lines).isEmpty(),
                        h.onClient(PeekScenario::lines));
                peekLeftOn = true;
            } finally {
                if (!peekLeftOn) {
                    h.command("hoard peek on");
                }
            }

            h.ctx().getInput().releaseKey(options -> options.keyShift);
            released = true;
            h.waitFor("card cleared after standing up", mc -> lines(mc).isEmpty(), 5 * Harness.SECOND);
            h.checkEquals("no card once the player stands up while still looking at the chest",
                    List.of(), h.onClient(PeekScenario::lines));
        } finally {
            if (!released) {
                h.ctx().getInput().releaseKey(options -> options.keyShift);
            }
        }

        tooltipScreenshot(h);
    }

    /**
     * The design's own end-to-end check for the storage tooltip is a screenshot, not an assertion --
     * driving a hovered inventory slot costs more than it proves, and the line's wording is already
     * pinned by {@code tooltip.TooltipTextTest}. Best-effort: if the diamond's slot cannot be found
     * (a layout this reflection guess did not anticipate), the screenshot is still taken, just not
     * necessarily hovering anything.
     */
    private static void tooltipScreenshot(Harness h) {
        h.serverCommand("item replace entity " + Harness.PLAYER + " hotbar.0 with minecraft:diamond 1");
        h.ctx().waitTicks(2);
        h.ctx().getInput().pressKey(options -> options.keyInventory);
        h.ctx().waitForScreen(AbstractContainerScreen.class);
        double[] center = h.onClient(PeekScenario::diamondSlotCenter);
        h.check("found the diamond's slot to hover for the tooltip screenshot", center != null,
                center == null ? "slot not found; screenshotting without hovering it" : List.of(center[0], center[1]));
        if (center != null) {
            h.ctx().getInput().setCursorPos(center[0], center[1]);
        }
        h.ctx().waitTicks(10);
        h.screenshot("tooltip");
        h.closeScreen();
    }

    /**
     * The centre, in the same screen-space {@code TestInput.setCursorPos} takes, of the inventory
     * slot holding a diamond -- or {@code null} if the open screen is not a container screen, no
     * slot holds one, or {@code AbstractContainerScreen}'s {@code leftPos}/{@code topPos} (protected,
     * and this class is not in that package) could not be read by reflection.
     */
    private static double[] diamondSlotCenter(Minecraft mc) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            return null;
        }
        Slot target = null;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.hasItem() && slot.getItem().is(Items.DIAMOND)) {
                target = slot;
                break;
            }
        }
        if (target == null) {
            return null;
        }
        try {
            Field leftField = AbstractContainerScreen.class.getDeclaredField("leftPos");
            Field topField = AbstractContainerScreen.class.getDeclaredField("topPos");
            leftField.setAccessible(true);
            topField.setAccessible(true);
            int leftPos = leftField.getInt(screen);
            int topPos = topField.getInt(screen);
            return new double[]{leftPos + target.x + 8, topPos + target.y + 8};
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Undoes the sneak reset a screen leaves behind — see the class javadoc. {@code releaseKey}
     * first is required: {@code TestInput}'s own bookkeeping still thinks the key from the original
     * {@code holdKey} is down, so a bare {@code holdKey} here would be a no-op.
     */
    private static void reassertCrouch(Harness h) {
        h.ctx().getInput().releaseKey(options -> options.keyShift);
        h.ctx().getInput().holdKey(options -> options.keyShift);
    }

    private static void lookAtAndSettle(Harness h, BlockPos pos) {
        h.ctx().getInput().lookAt(pos);
        h.ctx().waitTicks(2);
    }

    private static List<String> lines(Minecraft mc) {
        return PeekRenderer.currentLinesForTest();
    }
}
