package dev.hoardkeeper.deposit;

import dev.hoardkeeper.HoardkeeperConfig;
import dev.hoardkeeper.HoardkeeperMod;
import dev.hoardkeeper.capture.MenuSlotCounts;
import dev.hoardkeeper.chat.ScanChat;
import dev.hoardkeeper.index.StorageIndex;
import dev.hoardkeeper.index.StorageIndexCache;
import dev.hoardkeeper.index.ContainerView;
import dev.hoardkeeper.observe.PassiveObserver;
import dev.hoardkeeper.scan.ContainerCandidate;
import dev.hoardkeeper.scan.ContainerDiscovery;
import dev.hoardkeeper.scan.InteractionSender;
import dev.hoardkeeper.scan.ScanArea;
import dev.hoardkeeper.scan.ScanController;
import dev.hoardkeeper.scan.PhantomMenu;
import dev.hoardkeeper.search.ContainerHit;
import dev.hoardkeeper.search.SearchService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The singleplayer deposit — spec 2026-09-28-singleplayer-deposit-design.md. Two ways in: the mode
 * ({@code /hoard deposit}), which fills every container the player walks past that already
 * holds something they carry, and the sneak-right-click with an empty hand, which fills the one
 * container clicked.
 * {@link DepositRules} decides what moves; this class opens, clicks, records and closes.
 *
 * <p><b>Why its own open path.</b> The scanner swallows the open-screen packet before a menu
 * exists, so nothing can be clicked on its path. Here the packet is swallowed too — the player sees
 * no screen — but the menu is built and installed as {@code player.containerMenu}, so the contents
 * packet fills it and quick-move clicks work exactly as a shift-click in the GUI would.
 *
 * <p><b>Shares the interaction with the scanner.</b> {@link InteractionSender#open} marks the
 * click as the mod's own, which keeps the passive observer and the search from taking it for the
 * player's. The two never have a request in flight together: the deposit waits while a scan runs.
 *
 * <p>Client thread only: every entry point is a tick, a packet callback on the client thread (see
 * the mixin), a command, or a Fabric event raised on the client thread.
 */
public final class DepositService {

    private static final DepositService INSTANCE = new DepositService();

    /** Quick-move clicks per tick. Singleplayer has no anti-cheat to be polite to, but the menu does. */
    private static final int CLICKS_PER_TICK = 4;
    /** How far around the eye to look for containers in reach. Reach is ~4.5; the check is exact. */
    private static final int REACH_SEARCH = 6;
    /** How often the lit set is recomputed, in ticks. */
    private static final int HIGHLIGHT_EVERY = 10;
    /** How often the action bar is refreshed, in ticks; the vanilla bar fades after about three seconds. */
    private static final int BAR_EVERY = 20;
    private static final float STROKE_WIDTH = 2.0f;
    private static final float LABEL_SCALE = 0.7f;
    /** How long a container that just took something stays green. */
    private static final int FLASH_TICKS = 30;

    public static DepositService get() {
        return INSTANCE;
    }

    /** One container being filled: from the interaction to the close. */
    private static final class Request {
        final ContainerCandidate container;
        final boolean single;
        /** Moved forward while a screen is open, so a pause does not run the clock out. */
        int sentTick;
        int containerId = -1;
        String menuTypeId;
        String title;
        boolean opened;
        boolean contentsIn;
        List<Integer> toClick;
        int moved;
        final Set<DepositRules.Signature<ItemKey>> full = new java.util.HashSet<>();

        Request(ContainerCandidate container, boolean single, int sentTick) {
            this.container = container;
            this.single = single;
            this.sentTick = sentTick;
        }
    }

    /** A container the highlight lights, and what the player carries that goes there. */
    private record Lit(ContainerHit hit, List<String> ids, int firstStacks) {
    }

    /** A container that just took something: drawn green with "+N" over it for a moment. */
    private record Flash(int[] pos, int[] secondaryPos, int moved, int untilTick) {
    }

    private boolean on;
    private int tick;
    private int lastSendTick = Integer.MIN_VALUE / 2;
    private int movedTotal;
    private Request request;
    private final Deque<int[]> clicked = new ArrayDeque<>();
    private final DepositMemory<ItemKey> memory = new DepositMemory<>();
    private List<Lit> lit = List.of();
    /** Containers that did not answer an open; skipped until the player walks out of reach and back. */
    private final Set<Long> unanswered = new java.util.HashSet<>();
    private final List<Flash> flashes = new ArrayList<>();
    /** The container the held use key already asked about; holding the key repeats the use. */
    private long heldClick = Long.MIN_VALUE;
    /** Whether the mode has lit something since it last said "all put away", so it says so once. */
    private boolean hadLit;
    /** Until this tick the action bar keeps the sneak-click's own line instead of the mode's. */
    private int barQuietUntil;
    /** The last tick a stack moved; the mode's bar shows while something is happening. */
    private int lastMovedTick = Integer.MIN_VALUE / 2;

    private DepositService() {
    }

    // =========================================================================
    // Commands
    // =========================================================================

    /** {@code /hoard deposit [on|off]}; {@code null} toggles. */
    public void set(Minecraft mc, Boolean wanted) {
        boolean turnOn = wanted == null ? !on : wanted;
        if (turnOn == on) {
            ScanChat.info(on ? DepositText.alreadyOn() : DepositText.alreadyOff());
            return;
        }
        if (turnOn) {
            if (!singleplayer(mc)) {
                ScanChat.info(DepositText.notSingleplayer());
                return;
            }
            on = true;
            movedTotal = 0;
            memory.clear();
            ScanChat.info(DepositText.on());
            LocalPlayer player = mc.player;
            if (player != null && DepositRules.carried(mainInventory(player.getInventory())).isEmpty()) {
                ScanChat.info(DepositText.nothingToPutAway());
            } else if (StorageIndexCache.get().current(mc) == null) {
                ScanChat.info(DepositText.noScanHere());
            }
            DepositSounds.modeOn(mc);
        } else {
            turnOff(true);
        }
    }

    public boolean isOn() {
        return on;
    }

    /** How many containers are lit right now; for the gametest. */
    public int litCount() {
        return lit.size();
    }

    private void turnOff(boolean say) {
        on = false;
        lit = List.of();
        hadLit = false;
        if (say) {
            ScanChat.info(DepositText.off(movedTotal));
            DepositSounds.modeOff(Minecraft.getInstance());
        }
    }

    // =========================================================================
    // Sneak-left-click (spec §8a)
    // =========================================================================

    /**
     * {@code UseBlockCallback}. Returns {@code true} when this is a deposit click — sneaking,
     * right-clicking a container with an empty main hand, in singleplayer — and the ordinary open
     * must be cancelled; the deposit then opens the container itself, without a screen.
     *
     * <p>The mod's own opens pass through untouched ({@link InteractionSender#isSendingOwnInteraction}),
     * and so does everything that is not a container, or not the main hand.
     */
    public boolean onUseBlock(Minecraft mc, InteractionHand hand, BlockPos pos) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (InteractionSender.isSendingOwnInteraction() || player == null || level == null
                || hand != InteractionHand.MAIN_HAND || !singleplayer(mc)
                || !player.getMainHandItem().isEmpty()
                // The key as well as the player state: the crouch flag is updated later in the tick.
                || !(player.isShiftKeyDown() || mc.options.keyShift.isDown())) {
            return false;
        }
        ContainerCandidate candidate = discovery().candidateAt(level, pos);
        if (candidate == null) {
            return false;
        }
        long k = key(candidate.pos);
        if (k == heldClick) {
            // The same press, repeating while the key is held: still cancelled, not asked again.
            return true;
        }
        heldClick = k;
        boolean alreadyAsked = (request != null && samePos(request.container.pos, candidate.pos))
                || clicked.stream().anyMatch(p -> samePos(p, candidate.pos));
        if (!alreadyAsked) {
            clicked.add(candidate.pos);
            DepositSounds.clicked(mc, candidate.pos);
        }
        return true;
    }

    // =========================================================================
    // Tick
    // =========================================================================

    public void onClientTick(Minecraft mc) {
        tick++;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        flashes.removeIf(f -> tick > f.untilTick());
        if (!mc.options.keyUse.isDown()) {
            heldClick = Long.MIN_VALUE;
        }
        if (!on && request == null && clicked.isEmpty()) {
            return;
        }
        if (!singleplayer(mc)) {
            reset();
            return;
        }

        unanswered.removeIf(k -> !player.isWithinBlockInteractionRange(BlockPos.of(k),
                -HoardkeeperMod.config().reachMargin));
        if (request != null) {
            advance(mc, player);
        } else {
            trySend(mc, player);
        }

        if (on && tick % HIGHLIGHT_EVERY == 0) {
            refreshLit(mc, player);
        }
        // Only while something is happening (spec §8), never over a search's or a scan's bar, and
        // not over the sneak-click's own line while it is still being read.
        boolean happening = !lit.isEmpty() || tick - lastMovedTick < 3 * BAR_EVERY;
        if (on && happening && tick % BAR_EVERY == 0 && tick >= barQuietUntil
                && !SearchService.get().ownsActionBar() && !ScanController.get().isRunning()) {
            ScanChat.actionBar(DepositText.bar(movedTotal, lit.size(), nearestMeters(player)));
        }
    }

    /** Draws the lit containers. Nothing while a search has its own lit — spec §7a, "a search wins". */
    public void emitGizmos(Minecraft mc) {
        HoardkeeperConfig config = HoardkeeperMod.config();
        if (!config.gizmosEnabled || mc.level == null) {
            return;
        }
        // The confirmation flash is drawn whatever else is lit: it is the answer to what just happened.
        GizmoStyle done = GizmoStyle.strokeAndFill(config.colorScanned, STROKE_WIDTH, (config.colorScanned & 0x00FFFFFF) | 0x50000000);
        for (Flash f : flashes) {
            Gizmos.cuboid(blockPos(f.pos()), done).setAlwaysOnTop();
            if (f.secondaryPos() != null) {
                Gizmos.cuboid(blockPos(f.secondaryPos()), done).setAlwaysOnTop();
            }
            Gizmos.billboardTextOverBlock(DepositText.flash(f.moved()), blockPos(f.pos()), 0,
                    config.colorScanned, LABEL_SCALE).setAlwaysOnTop();
        }
        if (!on || lit.isEmpty() || !SearchService.get().lit().isEmpty()) {
            return;
        }
        GizmoStyle style = GizmoStyle.strokeAndFill(config.colorSearch, STROKE_WIDTH, config.colorSearchFill);
        for (Lit l : lit) {
            if (flashing(l.hit().pos())) {
                continue;
            }
            Gizmos.cuboid(blockPos(l.hit().pos()), style).setAlwaysOnTop();
            if (l.hit().secondaryPos() != null) {
                Gizmos.cuboid(blockPos(l.hit().secondaryPos()), style).setAlwaysOnTop();
            }
            Gizmos.billboardTextOverBlock(DepositText.label(l.ids(), l.firstStacks()), blockPos(l.hit().pos()), 0,
                    config.colorSearch, LABEL_SCALE).setAlwaysOnTop();
        }
    }

    // =========================================================================
    // Sending
    // =========================================================================

    private void trySend(Minecraft mc, LocalPlayer player) {
        if (blocked(mc, player)) {
            return;
        }
        HoardkeeperConfig config = HoardkeeperMod.config();
        if (tick - lastSendTick < config.minTicksBetweenOpens) {
            return;
        }

        ContainerCandidate target = null;
        boolean single = false;
        int[] half = null;
        if (!clicked.isEmpty()) {
            int[] pos = clicked.poll();
            ContainerCandidate candidate = discovery().candidateAt(mc.level, blockPos(pos));
            half = candidate == null ? null : reachableHalf(player, candidate);
            if (half == null) {
                ScanChat.actionBar(DepositText.clickedOutOfReach());
                barQuietUntil = tick + 3 * BAR_EVERY;
                return;
            }
            target = candidate;
            single = true;
        } else if (on) {
            ContainerCandidate candidate = nearestWorthOpening(mc, player);
            if (candidate == null) {
                return;
            }
            target = candidate;
            half = reachableHalf(player, candidate);
        }
        if (target == null || half == null) {
            return;
        }

        BlockPos at = blockPos(half);
        if (chestBlocked(mc.level, target.pos) || (target.secondaryPos != null && chestBlocked(mc.level, target.secondaryPos))) {
            // A blocked lid, on either half of a double chest, opens nothing.
            unanswered.add(key(target.pos));
            return;
        }
        request = new Request(target, single, tick);
        lastSendTick = tick;
        InteractionSender.open(mc, player, at, config.swingArmOnOpen);
    }

    /**
     * Whether the deposit must not act this tick: the scanner's gate (a screen open, sneaking, the
     * use key held, the connection stalled) plus a running scan, which owns the interaction.
     */
    private static boolean blocked(Minecraft mc, LocalPlayer player) {
        // Sneaking only matters with something in either hand: then a use places or uses the item
        // instead of opening the container. With both hands empty, a sneaking use opens it.
        boolean handsEmpty = player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
        return mc.gui.screen() != null
                || (!handsEmpty && (player.isShiftKeyDown() || player.isSecondaryUseActive()))
                || mc.options.keyUse.isDown()
                // The player's own right-click — the one that asked for this deposit, which was
                // cancelled — must have run out first, or onOpenScreen cannot tell whose menu arrives.
                || mc.rightClickDelay > 0
                || !player.connection.isAcceptingMessages()
                || ScanController.get().isRunning();
    }

    /** The nearest container in reach and in the 3×3 chunks that a visit could move something into. */
    private ContainerCandidate nearestWorthOpening(Minecraft mc, LocalPlayer player) {
        List<DepositStack<ItemKey>> main = mainInventory(player.getInventory());
        Set<DepositRules.Signature<ItemKey>> carried = DepositRules.carried(main);
        if (carried.isEmpty()) {
            return null;
        }
        Set<String> carriedIds = ids(carried);
        ScanArea area = area(player);
        StorageIndex index = StorageIndexCache.get().current(mc);
        ContainerDiscovery discovery = discovery();
        BlockPos centre = player.blockPosition();
        Vec3 eye = player.getEyePosition();

        ContainerCandidate best = null;
        double bestDistance = Double.MAX_VALUE;
        Set<Long> seen = new java.util.HashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-REACH_SEARCH, -REACH_SEARCH, -REACH_SEARCH),
                centre.offset(REACH_SEARCH, REACH_SEARCH, REACH_SEARCH))) {
            if (!area.coversBlock(pos.getX(), pos.getZ())) {
                continue;
            }
            ContainerCandidate candidate = discovery.candidateAt(mc.level, pos);
            if (candidate == null || !seen.add(key(candidate.pos))) {
                continue;
            }
            if (unanswered.contains(key(candidate.pos)) || !memory.worthOpening(key(candidate.pos), carried)) {
                continue;
            }
            if (index != null) {
                ContainerView view = index.positions().view(candidate.pos[0], candidate.pos[1], candidate.pos[2]);
                if (DepositMemory.indexRulesOut(view == null ? null : view.counts().keySet(), carriedIds)) {
                    continue;
                }
            }
            if (reachableHalf(player, candidate) == null) {
                continue;
            }
            double d = Vec3.atCenterOf(blockPos(candidate.pos)).distanceToSqr(eye);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best;
    }

    // =========================================================================
    // Packet callbacks — client thread, from ClientPacketListenerMixin
    // =========================================================================

    /**
     * The open-screen packet. Returns {@code true} to swallow the screen, having installed the menu
     * so the contents packet fills it and clicks work. Refuses a menu that is not ours: no request
     * waiting, a stale one, a player who right-clicked themselves in the last ticks, or a menu type
     * the mod does not read.
     */
    public boolean onOpenScreen(int id, MenuType<?> type, Component title) {
        Request r = request;
        if (id == 0 || r == null || r.opened) {
            return false;
        }
        HoardkeeperConfig config = HoardkeeperMod.config();
        Minecraft mc = Minecraft.getInstance();
        if (tick - r.sentTick > config.openTimeoutTicks || mc.rightClickDelay > 0) {
            request = null;
            return false;
        }
        String typeId = menuTypeId(type);
        if (MenuSlotCounts.expectedFor(typeId) <= 0 || mc.player == null) {
            request = null;
            return false;
        }
        AbstractContainerMenu menu = type.create(id, mc.player.getInventory());
        mc.player.containerMenu = menu;
        // The client drops the server's late updates for this id once close() restores the
        // inventory menu; PhantomMenu catches the player's own slots among them.
        PhantomMenu.opened(id, menu.slots.size() - 36);
        r.containerId = id;
        r.menuTypeId = typeId;
        r.title = title.getString();
        r.opened = true;
        return true;
    }

    /**
     * The contents packet, before the game applies it to the menu installed above. Never swallowed:
     * the game has to fill the menu. The clicks start next tick, once it has.
     */
    public void onContainerContent(int id) {
        Request r = request;
        if (r != null && r.opened && id == r.containerId) {
            r.contentsIn = true;
        }
    }

    // =========================================================================
    // Filling
    // =========================================================================

    private void advance(Minecraft mc, LocalPlayer player) {
        Request r = request;
        if (mc.gui.screen() != null) {
            // The player opened something — chat, the inventory, the pause menu (which in singleplayer
            // also pauses the server). Nothing is clicked or closed under their screen, and the wait
            // does not count against the request.
            r.sentTick++;
            return;
        }
        HoardkeeperConfig config = HoardkeeperMod.config();
        int limit = r.opened ? config.contentTimeoutTicks : config.openTimeoutTicks;
        if (!r.contentsIn) {
            if (tick - r.sentTick > limit) {
                if (r.opened) {
                    close(player, r.containerId);
                }
                // Not retried until the player has walked out of reach and back (spec §7): the
                // nearest container that never answers would otherwise starve every other one.
                unanswered.add(key(r.container.pos));
                request = null;
            }
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != r.containerId) {
            // Something else closed it — the server, or the player. Nothing to finish.
            request = null;
            return;
        }

        if (r.toClick == null) {
            r.toClick = plan(menu, player.getInventory());
        }
        int clicks = 0;
        while (!r.toClick.isEmpty() && clicks < CLICKS_PER_TICK) {
            int slot = r.toClick.remove(0);
            ItemStack before = menu.getSlot(slot).getItem().copy();
            // Re-read before every click: the stack may have changed, and an item that already did
            // not fit will not fit from the next slot either.
            DepositRules.Signature<ItemKey> signature = DepositRules.movable(ItemKey.describe(before)).orElse(null);
            if (signature == null || r.full.contains(signature)) {
                continue;
            }
            mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
            clicks++;
            ItemStack after = menu.getSlot(slot).getItem();
            if (after.getCount() < before.getCount() || after.isEmpty()) {
                r.moved++;
                lastMovedTick = tick;
            }
            if (!after.isEmpty()) {
                // Did not all fit: the container is full for this item. What is left stays.
                r.full.add(signature);
            }
        }
        if (!r.toClick.isEmpty()) {
            return;
        }
        finish(mc, player, menu, r);
    }

    /**
     * The menu slots to quick-move: the player's 27 main-inventory slots whose stack goes into this
     * container (spec §3, §4). The hotbar is never among them — only inventory slots 9–35 are
     * looked at — and armour and the off-hand are not in a container menu at all.
     */
    private static List<Integer> plan(AbstractContainerMenu menu, Inventory inventory) {
        List<DepositStack<ItemKey>> containerStacks = new ArrayList<>();
        List<Integer> mainSlots = new ArrayList<>();
        List<DepositStack<ItemKey>> main = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == inventory) {
                int index = slot.getContainerSlot();
                if (index >= Inventory.SELECTION_SIZE && index < Inventory.INVENTORY_SIZE) {
                    mainSlots.add(slot.index);
                    main.add(ItemKey.describe(slot.getItem()));
                }
            } else {
                containerStacks.add(ItemKey.describe(slot.getItem()));
            }
        }
        List<Integer> plan = new ArrayList<>();
        for (int i : DepositRules.slotsToMove(main, DepositRules.held(containerStacks))) {
            plan.add(mainSlots.get(i));
        }
        return plan;
    }

    private void finish(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Request r) {
        List<DepositStack<ItemKey>> containerStacks = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container != player.getInventory()) {
                containerStacks.add(ItemKey.describe(slot.getItem()));
            }
        }
        memory.visited(key(r.container.pos), DepositRules.held(containerStacks), r.full);
        // What the container holds now goes where a hand-closed container's contents go, so search,
        // tooltips and the highlight see the new state.
        PassiveObserver.get().recordDeposit(mc, r.container.pos, menu.getItems(), r.menuTypeId, r.title);
        movedTotal += r.moved;
        close(player, r.containerId);
        request = null;
        if (on) {
            refreshLit(mc, player);
        }
        // What just happened, three ways: a sound at the container, the container flashing green with
        // what went in, and a line in the action bar.
        if (r.moved > 0) {
            DepositSounds.deposited(mc, r.container.pos, r.moved);
            flashes.add(new Flash(r.container.pos, r.container.secondaryPos, r.moved, tick + FLASH_TICKS));
        } else if (r.single) {
            DepositSounds.nothingFits(mc, r.container.pos);
        }
        if (r.single) {
            ScanChat.actionBar(DepositText.clicked(r.moved));
            barQuietUntil = tick + 3 * BAR_EVERY;
        } else if (r.moved > 0 && !SearchService.get().ownsActionBar()) {
            ScanChat.actionBar(DepositText.depositedInto(r.moved, movedTotal, lit.size()));
            barQuietUntil = tick + 2 * BAR_EVERY;
        }
    }

    /**
     * Tells the server we are done and puts the player's own inventory menu back — without
     * {@code player.closeContainer()}, which also clears the screen and so would shut whatever the
     * player has just opened themselves.
     */
    private static void close(LocalPlayer player, int containerId) {
        player.connection.send(new ServerboundContainerClosePacket(containerId));
        if (player.containerMenu != null && player.containerMenu.containerId == containerId) {
            player.containerMenu = player.inventoryMenu;
        }
    }

    private boolean flashing(int[] pos) {
        return flashes.stream().anyMatch(f -> samePos(f.pos(), pos));
    }

    private static boolean chestBlocked(ClientLevel level, int[] pos) {
        BlockPos at = blockPos(pos);
        BlockState state = level.getBlockState(at);
        return state.getBlock() instanceof ChestBlock && ChestBlock.isChestBlockedAt(level, at);
    }

    // =========================================================================
    // Highlight (spec §7a)
    // =========================================================================

    /**
     * Recomputes the lit set, and says once when the last lit container has been dealt with: the
     * mode's "done", with a sound, so the player knows the walk is over without reading anything.
     */
    private void refreshLit(Minecraft mc, LocalPlayer player) {
        lit = computeLit(mc, player);
        if (!lit.isEmpty()) {
            hadLit = true;
        } else if (hadLit && request == null) {
            hadLit = false;
            DepositSounds.allPutAway(mc);
            if (!SearchService.get().ownsActionBar()) {
                ScanChat.actionBar(DepositText.allPutAway(movedTotal));
                barQuietUntil = tick + 3 * BAR_EVERY;
            }
        }
    }

    private List<Lit> computeLit(Minecraft mc, LocalPlayer player) {
        StorageIndex index = StorageIndexCache.get().current(mc);
        if (index == null) {
            return List.of();
        }
        List<DepositStack<ItemKey>> main = mainInventory(player.getInventory());
        Set<DepositRules.Signature<ItemKey>> carried = DepositRules.carried(main);
        if (carried.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> stacksOf = new java.util.HashMap<>();
        for (DepositStack<ItemKey> stack : main) {
            DepositRules.movable(stack).ifPresent(sig -> stacksOf.merge(ItemKey.indexId(sig), 1, Integer::sum));
        }
        ScanArea area = area(player);
        Map<Long, ContainerHit> hits = new LinkedHashMap<>();
        Map<Long, Set<String>> idsAt = new LinkedHashMap<>();
        for (String id : ids(carried)) {
            for (ContainerHit hit : index.search().hits(id)) {
                if (!area.coversBlock(hit.pos()[0], hit.pos()[2])) {
                    continue;
                }
                long k = key(hit.pos());
                if (Boolean.FALSE.equals(memory.stillWanted(k, carried))) {
                    continue;
                }
                hits.putIfAbsent(k, hit);
                idsAt.computeIfAbsent(k, x -> new TreeSet<>()).add(id);
            }
        }
        Vec3 eye = player.getEyePosition();
        double[] e = {eye.x, eye.y, eye.z};
        int cap = Math.max(1, HoardkeeperMod.config().searchMaxHighlights);
        return hits.entrySet().stream()
                .sorted(Comparator.comparingDouble(en -> en.getValue().distanceSquaredFrom(e)))
                .limit(cap)
                .map(en -> {
                    List<String> ids = List.copyOf(idsAt.get(en.getKey()));
                    return new Lit(en.getValue(), ids, stacksOf.getOrDefault(ids.get(0), 1));
                })
                .toList();
    }

    private int nearestMeters(LocalPlayer player) {
        if (lit.isEmpty()) {
            return -1;
        }
        Vec3 eye = player.getEyePosition();
        double[] e = {eye.x, eye.y, eye.z};
        return (int) Math.round(Math.sqrt(lit.get(0).hit().distanceSquaredFrom(e)));
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /** The player opened a container by hand: what it holds may have changed in any way. */
    public void onPlayerUsedBlock(BlockPos pos) {
        if (InteractionSender.isSendingOwnInteraction()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        ContainerCandidate candidate = level == null ? null : discovery().candidateAt(level, pos);
        if (candidate != null) {
            memory.forget(key(candidate.pos));
        }
    }

    public void onDisconnect() {
        reset();
    }

    /** Back to just-initialised, for disconnect and for the gametest harness between scenarios. */
    public void reset() {
        on = false;
        request = null;
        clicked.clear();
        memory.clear();
        unanswered.clear();
        flashes.clear();
        heldClick = Long.MIN_VALUE;
        hadLit = false;
        barQuietUntil = 0;
        lit = List.of();
        movedTotal = 0;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Set only by the client gametest harness, which is a dedicated server reached as localhost and
     * so never singleplayer. Never set by the shipped mod: there, singleplayer is the only place
     * the deposit runs (spec §5), with no override.
     */
    private static boolean anyServerForGameTest;

    /** Test seam — see {@link #anyServerForGameTest}. */
    public static void allowAnyServerForGameTest(boolean allow) {
        anyServerForGameTest = allow;
    }

    private static boolean singleplayer(Minecraft mc) {
        return mc.hasSingleplayerServer() || anyServerForGameTest;
    }

    private static List<DepositStack<ItemKey>> mainInventory(Inventory inventory) {
        List<DepositStack<ItemKey>> main = new ArrayList<>();
        for (int i = Inventory.SELECTION_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
            main.add(ItemKey.describe(inventory.getItem(i)));
        }
        return main;
    }

    private static Set<String> ids(Set<DepositRules.Signature<ItemKey>> signatures) {
        Set<String> ids = new TreeSet<>();
        for (DepositRules.Signature<ItemKey> s : signatures) {
            ids.add(ItemKey.indexId(s));
        }
        return ids;
    }

    private static ScanArea area(LocalPlayer player) {
        return ScanArea.ofChunks(player.getBlockX(), player.getBlockZ(), 1);
    }

    private static ContainerDiscovery discovery() {
        return new ContainerDiscovery(HoardkeeperMod.config());
    }

    private static int[] reachableHalf(LocalPlayer player, ContainerCandidate candidate) {
        double margin = -HoardkeeperMod.config().reachMargin;
        if (player.isWithinBlockInteractionRange(blockPos(candidate.pos), margin)) {
            return candidate.pos;
        }
        if (candidate.secondaryPos != null
                && player.isWithinBlockInteractionRange(blockPos(candidate.secondaryPos), margin)) {
            return candidate.secondaryPos;
        }
        return null;
    }

    private static String menuTypeId(MenuType<?> type) {
        Identifier key = type == null ? null : BuiltInRegistries.MENU.getKey(type);
        return key == null ? "?" : key.toString();
    }

    private static boolean samePos(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private static long key(int[] pos) {
        return BlockPos.asLong(pos[0], pos[1], pos[2]);
    }

    private static BlockPos blockPos(int[] pos) {
        return new BlockPos(pos[0], pos[1], pos[2]);
    }
}
