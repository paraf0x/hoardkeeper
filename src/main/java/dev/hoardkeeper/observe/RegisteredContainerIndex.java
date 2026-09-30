package dev.hoardkeeper.observe;

import dev.hoardkeeper.measured.MeasuredMapStore;
import dev.hoardkeeper.store.ScanStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The registered containers of a storage, read off the measured map — the disk half of
 * {@link RegisteredContainers}, which owns the rule itself.
 *
 * <p><b>Why the parse is cached per file.</b> This answers a question asked when a player closes a
 * container, and the file behind it is a realm's whole {@code containers.jsonl} — hundreds of rows
 * for a real base. Re-parsing that on every chest is exactly the cost {@code PassiveObserver} keeps
 * off the client thread everywhere else, so the positions are kept against the file's size and
 * modification time: a rewrite moves both, anything else is answered from memory.
 *
 * <p><b>An observation of our own must not invalidate that cache.</b> Every observation projected
 * onto the map appends a line to this very file, so a size/mtime check alone would mean each chest
 * closed inside a storage throws away the parse the next one needs — the whole map re-parsed on the
 * client thread, per chest, which is precisely what the cache exists to prevent. Two things close
 * that: {@link #projected} folds the row's position straight into the cached set, and
 * {@link MeasuredMapStore#isOwnContentUpdate} says whether the new size and mtime are the ones one
 * of this client's own appends left behind — and spec §4.2 guarantees such an append changes no
 * position. Every other change to the file still fails the size/mtime check and is re-parsed.
 *
 * <p>Only positions are kept, never contents — a set of longs per file, a few kilobytes for a base
 * whose rows are megabytes.
 */
public final class RegisteredContainerIndex {

    /** Positions parsed out of one {@code containers.jsonl}, valid while size and mtime hold. */
    private record Cached(long size, long modified, Set<Long> positions) {
    }

    private final Map<Path, Cached> cache = new HashMap<>();

    /**
     * Every position the measured map holds a container at. Never throws: a file that cannot be
     * read contributes nothing, which refuses observations rather than inventing them.
     *
     * <p>Reads the map rather than the scan sessions on purpose. A session is deleted once it has
     * uploaded (design spec §6), so a check against sessions would quietly stop recognising
     * containers the storage certainly has.
     */
    public Set<Long> positions(Path measuredContainers) {
        return positionsOf(measuredContainers);
    }

    /**
     * Folds one just-projected observation's position into the cached set for {@code file}, instead
     * of waiting for the append behind it to force a re-parse. A no-op when nothing is cached for
     * that file yet — the next {@link #positions} call reads it in full anyway.
     *
     * <p>Today the position is provably already there: {@code PassiveObserver} only projects an
     * observation the gate has already found registered. It is added regardless so this cache
     * states what it holds rather than inferring it from another class's rule, and so a future
     * source of content updates cannot silently leave it short.
     */
    public void projected(Path measuredContainers, int[] pos) {
        Cached cached = cache.get(measuredContainers);
        if (cached != null) {
            cache.put(measuredContainers, new Cached(cached.size(), cached.modified(),
                    RegisteredContainers.with(cached.positions(), pos)));
        }
    }

    /** Drops every cached parse. Called when the client leaves a server: other files entirely. */
    public void forget() {
        cache.clear();
    }

    private Set<Long> positionsOf(Path file) {
        long size;
        long modified;
        try {
            if (!Files.isRegularFile(file)) {
                cache.remove(file);
                return Set.of();
            }
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            // A file we cannot stat is one we simply stat again next time — never a crash on the
            // client thread, and never a silently widened rule.
            cache.remove(file);
            return Set.of();
        }

        Cached cached = cache.get(file);
        if (cached != null && cached.size() == size && cached.modified() == modified) {
            return cached.positions();
        }
        if (cached != null && MeasuredMapStore.isOwnContentUpdate(file, size, modified)) {
            // Our own append, and spec §4.2 lets a content update change only what is at a position
            // it already holds — so the set is still right and only the stat it is filed under has
            // moved on. See the class javadoc.
            cache.put(file, new Cached(size, modified, cached.positions()));
            return cached.positions();
        }

        // The same tolerant reader export and upload use: a torn or hand-edited line costs itself
        // and nothing else.
        Set<Long> positions = RegisteredContainers.positionsOf(
                ScanStorage.readContainers(file));
        cache.put(file, new Cached(size, modified, positions));
        return positions;
    }
}
