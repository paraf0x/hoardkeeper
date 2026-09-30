package dev.hoardkeeper.deposit;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the deposit mode learned about each container it opened — spec §6, "remembered contents".
 *
 * <p>Without it the mode would re-open every chest in reach every few ticks. With it a container
 * is opened again only when opening it could now achieve something: the player carries a stack it
 * holds that did not already fail to fit there. Full is per item: a chest that ran out of room for
 * cobblestone may still merge dirt into a part stack.
 *
 * <p>Positions are the canonical position of a container (for a double chest, the one half
 * {@code ContainerDiscovery} names), packed into a {@code long} by the caller. Pure: no world.
 */
public final class DepositMemory<K> {

    /**
     * @param held what the container held after the visit
     * @param full the items a stack of was left in the inventory because it did not fit
     */
    record Visit<K>(Set<DepositRules.Signature<K>> held, Set<DepositRules.Signature<K>> full) {
    }

    private final Map<Long, Visit<K>> visits = new HashMap<>();

    /** Records a visit: what the container held once the deposit was done, and what did not fit. */
    public void visited(long pos, Set<DepositRules.Signature<K>> held, Set<DepositRules.Signature<K>> full) {
        visits.put(pos, new Visit<>(Set.copyOf(held), Set.copyOf(full)));
    }

    /**
     * Whether a visit to {@code pos} could move anything now. A container never visited always
     * could — the live contents decide, not a guess. For one visited, something carried must be in
     * it and not among what already did not fit; that holds until {@link #forget}.
     */
    public boolean worthOpening(long pos, Set<DepositRules.Signature<K>> carried) {
        Visit<K> visit = visits.get(pos);
        if (visit == null) {
            return true;
        }
        for (DepositRules.Signature<K> signature : carried) {
            if (DepositRules.accepts(visit.held(), signature) && !visit.full().contains(signature)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a visit to {@code pos} is still expected to take something, for the highlight: known
     * to hold something carried and not known to be full. A container never visited answers
     * {@code null} — the index has to say.
     */
    public Boolean stillWanted(long pos, Set<DepositRules.Signature<K>> carried) {
        Visit<K> visit = visits.get(pos);
        return visit == null ? null : worthOpening(pos, carried);
    }

    /** The player opened this container by hand; what it holds may have changed in any way. */
    public void forget(long pos) {
        visits.remove(pos);
    }

    /** Everything, as when the mode turns off or the world changes. */
    public void clear() {
        visits.clear();
    }

    /**
     * Whether the index alone rules a container out: it knows the container ({@code indexIds} not
     * {@code null}) and none of the item ids the player carries is in it. A container the index does
     * not know is never ruled out. By id, not by signature, because that is all the index keeps — so
     * this can let a container through that the live contents then turn down, never the reverse.
     */
    public static boolean indexRulesOut(Set<String> indexIds, Set<String> carriedIds) {
        return indexIds != null && Collections.disjoint(indexIds, carriedIds);
    }
}
