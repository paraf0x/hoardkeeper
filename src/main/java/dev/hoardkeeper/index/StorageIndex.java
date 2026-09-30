package dev.hoardkeeper.index;

import dev.hoardkeeper.model.ScannedContainer;
import dev.hoardkeeper.search.SearchIndex;

import java.util.List;

/**
 * One scan's rows, inverted both directions at once: item → containers, and position → one
 * container. Design spec §4.2.
 *
 * <p>Building these separately from two calls over the same row list would still be correct — the
 * two halves do not interact — but it would mean two consumers each deciding for themselves when to
 * re-invert, over rows that are already in memory together. {@link #of} inverts once and hands back
 * both, so {@link dev.hoardkeeper.index.StorageIndexCache}, the cache that owns the read this
 * composes, has exactly one thing to build and exactly one thing to cache.
 *
 * <p>{@link #search} is {@link SearchIndex}, entirely unmodified — its javadoc, its behaviour and
 * its own tests stay exactly as they were before this record existed; it is simply a field here
 * rather than something {@code SearchService} builds and owns directly. {@link #positions} is the
 * other direction, {@link PositionIndex}, the same rows read "what is at this spot" instead of
 * "where is this item".
 */
public record StorageIndex(SearchIndex search, PositionIndex positions) {

    /** Inverts {@code rows} both ways. Each half applies its own rules for what it drops; see both. */
    public static StorageIndex of(List<ScannedContainer> rows) {
        return new StorageIndex(SearchIndex.of(rows), PositionIndex.of(rows));
    }
}
