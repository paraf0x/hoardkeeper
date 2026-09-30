package dev.hoardkeeper.observe;

import dev.hoardkeeper.scan.ContainerKind;

/**
 * Decides whether a container the player just opened by hand should be recorded as a passive
 * observation. Design spec §3, as a function of plain inputs — no Minecraft type crosses this
 * boundary — so every clause is a unit test rather than a game session. The caller resolves the
 * block's {@link ContainerKind}, the menu type and the "inside a scanned area" question first;
 * this class only combines those answers.
 *
 * <p><b>Deviation from the spec text:</b> {@code insideStorageArea} is computed by the caller over
 * scan sessions whose {@code purpose} is {@code "storage"} only. A build-site session is not a
 * storage session, so a container inside a build site but outside every storage scan is still
 * outside the area this gate cares about.
 *
 * <p><b>The resolved kind is not always the kind that went in.</b> Spec §3.4: the slot count
 * derived from the menu at close outranks whatever the block state claimed, exactly as {@code
 * MenuSlotCounts} already trusts the wire size over a menu type id. A block that says double whose
 * menu delivered 27 container slots means the other half was broken while the GUI was open — filed
 * as the single kind, with no {@code secondaryPos} (that part is the caller's job, not this
 * class's). A block that says single whose menu delivered 54 is something lying (a plugin) and is
 * refused outright rather than "upgraded" to a double nobody saw.
 */
public final class ObservationGate {

    /** Slot count of every single-container kind this mod scans (chest, barrel, shulker box). */
    private static final int SINGLE_SLOTS = 27;

    /** Slot count of a paired double chest. */
    private static final int DOUBLE_SLOTS = 54;

    private ObservationGate() {
    }

    /** Why {@link #decide} refused to record an observation. */
    public enum Refusal {
        NOT_A_CONTAINER,
        UNKNOWN_MENU,
        SLOT_COUNT_MISMATCH,
        OUTSIDE_SCANNED_AREA,
        NOT_A_REGISTERED_CONTAINER,
        DISABLED
    }

    /**
     * The outcome of {@link #decide}: either {@code allowed()} with the {@link #resolvedKind()} to
     * file the observation under, or refused with a {@link #reason()} and no kind.
     */
    public record Decision(boolean allowed, Refusal reason, ContainerKind resolvedKind) {

        public static Decision allow(ContainerKind resolvedKind) {
            return new Decision(true, null, resolvedKind);
        }

        public static Decision refuse(Refusal reason) {
            return new Decision(false, reason, null);
        }
    }

    /**
     * Decides whether the container just closed should be recorded, and under which kind.
     *
     * <p>Clause order is deliberate — cheapest and most specific first, so a refusal names the real
     * cause rather than whichever check happened to run last: {@link Refusal#DISABLED}, then
     * {@link Refusal#NOT_A_CONTAINER}, then {@link Refusal#UNKNOWN_MENU}, then
     * {@link Refusal#SLOT_COUNT_MISMATCH} (which is also where the kind gets resolved — see the
     * class javadoc), then {@link Refusal#OUTSIDE_SCANNED_AREA}.
     *
     * @param kindOrNull             the block's {@link ContainerKind}, or {@code null} when it is
     *                               not a kind this mod scans (an ender chest, a furnace, ...) —
     *                               {@code ContainerDiscovery.candidateAt}'s result flows straight
     *                               into this parameter
     * @param expectedSlotsForMenu   the vanilla slot count implied by the menu type id the
     *                               open-screen packet carried, or {@code <= 0} when that menu type
     *                               is not one this mod understands
     * @param derivedSlotCount       the container slot count derived from the menu's actual slot
     *                               list at close, or {@code <= 0} when that derivation failed
     * @param insideStorageArea      whether the position lies inside some storage-purpose scan
     *                               session's area (see the class javadoc's deviation note)
     * @param passiveObserve         the feature's on/off switch
     * @param passiveObserveAnywhere when {@code true}, skips the {@code insideStorageArea} check
     */
    public static Decision decide(ContainerKind kindOrNull, int expectedSlotsForMenu, int derivedSlotCount,
                                   boolean insideStorageArea, boolean registeredContainer,
                                   boolean passiveObserve, boolean passiveObserveAnywhere) {
        if (!passiveObserve) {
            return Decision.refuse(Refusal.DISABLED);
        }
        if (kindOrNull == null) {
            return Decision.refuse(Refusal.NOT_A_CONTAINER);
        }
        if (expectedSlotsForMenu <= 0) {
            return Decision.refuse(Refusal.UNKNOWN_MENU);
        }

        Decision resolved = resolveKind(kindOrNull, derivedSlotCount);
        if (!resolved.allowed()) {
            return resolved;
        }

        if (!insideStorageArea && !passiveObserveAnywhere) {
            return Decision.refuse(Refusal.OUTSIDE_SCANNED_AREA);
        }
        // Being in the right place is not the same as being part of the storage. A shulker box set
        // down for one trip, or a chest somebody added after the last scan, stands inside the area
        // and belongs to nothing: an observation of it would not be updating a container's contents,
        // it would be inventing a container. Only a scan registers containers; this only updates
        // what one already found.
        if (!registeredContainer && !passiveObserveAnywhere) {
            return Decision.refuse(Refusal.NOT_A_REGISTERED_CONTAINER);
        }
        return resolved;
    }

    /**
     * Resolves {@code kindOrNull} against {@code derivedSlotCount}, downgrading a double chest to
     * its single variant when the derived count says single, and refusing when a single kind's
     * count says double. {@link ContainerKind#asDouble()} already covers the opposite direction
     * (single to double); the downgrade lives here, as a {@code switch}, rather than growing that
     * enum.
     */
    private static Decision resolveKind(ContainerKind kindOrNull, int derivedSlotCount) {
        return switch (kindOrNull) {
            case CHEST_DOUBLE -> resolveDouble(derivedSlotCount, ContainerKind.CHEST_DOUBLE, ContainerKind.CHEST);
            case TRAPPED_CHEST_DOUBLE ->
                    resolveDouble(derivedSlotCount, ContainerKind.TRAPPED_CHEST_DOUBLE, ContainerKind.TRAPPED_CHEST);
            case CHEST, TRAPPED_CHEST, BARREL, SHULKER_BOX -> derivedSlotCount == SINGLE_SLOTS
                    ? Decision.allow(kindOrNull)
                    : Decision.refuse(Refusal.SLOT_COUNT_MISMATCH);
        };
    }

    /**
     * A block state claiming {@code doubleKind}: kept as double when the menu really held
     * {@link #DOUBLE_SLOTS}, downgraded to {@code singleKind} when it held only
     * {@link #SINGLE_SLOTS} (the other half broke mid-open), refused for anything else.
     */
    private static Decision resolveDouble(int derivedSlotCount, ContainerKind doubleKind, ContainerKind singleKind) {
        if (derivedSlotCount == DOUBLE_SLOTS) {
            return Decision.allow(doubleKind);
        }
        if (derivedSlotCount == SINGLE_SLOTS) {
            return Decision.allow(singleKind);
        }
        return Decision.refuse(Refusal.SLOT_COUNT_MISMATCH);
    }
}
