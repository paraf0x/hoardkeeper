package dev.hoardkeeper.scan;

import dev.hoardkeeper.HoardkeeperConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds container block entities (chests, trapped chests, barrels, shulker boxes) within a scan
 * session's radius, either by sweeping every currently loaded chunk in range ({@link #sweep}) or
 * incrementally as a single chunk finishes loading ({@link #scanChunk}).
 * <p>
 * Discovered containers are recorded on {@link ScanSession} as {@link ContainerCandidate}s. Both
 * halves of a double chest are collapsed into a single candidate — see {@link #tryPairDoubleChest}.
 * A chest half whose partner cannot yet be confirmed (its chunk isn't loaded) is deliberately left
 * undiscovered rather than registered alone, so a later sweep can re-examine it once both halves
 * are visible — see {@link #registerContainer}.
 * <p>
 * The classify-and-pair decision itself lives in {@link #candidateAt}, which takes no
 * {@link ScanSession} of its own — that is what lets a caller who isn't running a scan at all
 * (the passive container observer) ask it the same question the sweep does.
 */
public final class ContainerDiscovery {
    private static final Logger LOGGER = LoggerFactory.getLogger("hoardkeeper");

    private final HoardkeeperConfig config;

    public ContainerDiscovery(HoardkeeperConfig config) {
        this.config = config;
    }

    /**
     * Sweeps every chunk the session's {@link ScanArea} touches and
     * that is currently loaded, discovering any containers not already known. Returns the total
     * number of candidates known to the session afterwards (not just newly found ones).
     */
    public int sweep(ClientLevel level, ScanSession session) {
        ScanArea area = session.area;
        int chunkRadius = area.sweepChunkRadius();
        int cx0 = area.originChunkX();
        int cz0 = area.originChunkZ();
        int inRadius = 0;
        int loaded = 0;

        for (int cx = cx0 - chunkRadius; cx <= cx0 + chunkRadius; cx++) {
            for (int cz = cz0 - chunkRadius; cz <= cz0 + chunkRadius; cz++) {
                inRadius++;
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                loaded++;
                scanChunk(level, chunk, session);
            }
        }

        session.chunksInRadius = inRadius;
        session.chunksLoaded = loaded;
        return session.candidates.size();
    }

    /**
     * Discovers containers within a single already-loaded chunk. Called both from {@link #sweep}
     * and directly from {@code ClientChunkEvents.CHUNK_LOAD} so a chunk that loads mid-scan is
     * picked up without waiting for the next periodic sweep.
     */
    public void scanChunk(ClientLevel level, LevelChunk chunk, ScanSession session) {
        ScanArea area = session.area;

        // This runs from CHUNK_LOAD as well as from sweep(), i.e. for chunks nobody checked against
        // the area first -- the player walking around loads them continuously. The per-block test
        // below is therefore the only thing standing between a chunk that merely happens to load and
        // its containers ending up in the scan.
        if (!area.coversChunk(chunk.getPos().x(), chunk.getPos().z())) {
            return;
        }

        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            long packed = pos.asLong();
            if (session.byPackedPos.containsKey(packed)) {
                continue;
            }

            // Horizontal only: a scan is a column, so a container's height above or below the origin
            // never excludes it. For a chunk-mode area every block of a covered chunk qualifies, and
            // this is what makes that true without a second code path.
            if (!area.coversBlock(pos.getX(), pos.getZ())) {
                continue;
            }

            // kindOf is checked here too, even though registerContainer (via candidateAt) checks it
            // again: this is what lets a block entity this mod doesn't scan at all -- a furnace, a
            // sign -- skip the rest of the pipeline instead of building a BlockPos array for nothing.
            if (kindOf(level.getBlockState(pos)) == null) {
                continue;
            }

            registerContainer(level, pos, session);
        }
    }

    /**
     * The candidate for the container at {@code pos}, with double-chest pairing applied, or
     * {@code null} when the block is not a kind this mod scans, when that kind is disabled in
     * config, or when a chest claims to be half of a pair whose partner does not validate.
     *
     * <p>Two callers, one rule: the discovery sweep and the passive observer.
     */
    public ContainerCandidate candidateAt(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        ContainerKind kind = kindOf(state);
        if (kind == null) {
            return null;
        }

        Block block = state.getBlock();
        if (block instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type != ChestType.SINGLE) {
                return tryPairDoubleChest(level, pos, state, block, type, kind);
            }
        }

        return new ContainerCandidate(toArray(pos), null, kind);
    }

    /**
     * Registers a classified container as a candidate, unless it is one half of a double chest
     * whose partner cannot be confirmed right now — see the "unpaired half" note below.
     * <p>
     * <b>Unpaired half:</b> when the block at {@code pos} is a {@link ChestBlock} (or {@link
     * TrappedChestBlock}) with {@code ChestBlock.TYPE != SINGLE}, this position is <em>never</em>
     * registered on its own, even if pairing fails — {@link #candidateAt} answers with {@code null}
     * in that case, and this method simply registers nothing. The player walking through the world
     * means the loaded/unloaded chunk boundary continuously sweeps across the scan radius, so a
     * double chest straddling that boundary at the wrong moment is an ordinary occurrence, not an
     * edge case. Registering the visible half alone would let it be scanned as a single-slot-count
     * container while its partner is scanned later as its own "double" — the same 54 slots counted
     * twice in the report totals, silently, with no signal that anything went wrong. Instead the
     * position is left out of both {@code session.candidates} and {@code session.byPackedPos}
     * entirely, so the next periodic sweep (or {@code CHUNK_LOAD} for the partner chunk)
     * re-examines it once both halves are loaded and pairs it correctly. The accepted trade-off: if
     * the partner chunk never loads for the whole session, this chest is simply never scanned —
     * visible as a gap between {@code known} and what a player can see stacked at that spot, rather
     * than a silent double-count. A genuinely {@code SINGLE} chest, and every barrel/shulker box
     * (which have no pairing concept), are unaffected and always register normally.
     * <p>
     * <b>Invariant:</b> every candidate in {@code session.candidates} is reachable from
     * {@code session.byPackedPos}. A chest half is either paired into exactly one candidate
     * registered under both of its packed positions, or not registered at all — it is never
     * registered alone. {@link #discardOrphan} exists purely as belt-and-braces for state left
     * behind by a single half that was registered before this rule existed within a running
     * session (e.g. by an older build resuming a snapshot); on a fresh sweep under the current
     * rule, neither packed position should already hold a stale single by the time pairing here
     * succeeds.
     */
    private void registerContainer(ClientLevel level, BlockPos pos, ScanSession session) {
        ContainerCandidate candidate = candidateAt(level, pos);
        if (candidate == null) {
            LOGGER.debug("deferring unpaired double chest half at {} — partner chunk not loaded", pos);
            return;
        }

        long packedPos = BlockPos.asLong(candidate.pos[0], candidate.pos[1], candidate.pos[2]);
        if (candidate.secondaryPos == null) {
            session.candidates.add(candidate);
            session.byPackedPos.put(packedPos, candidate);
            return;
        }

        long packedSecondary = BlockPos.asLong(
                candidate.secondaryPos[0], candidate.secondaryPos[1], candidate.secondaryPos[2]);

        // Either half may already be registered as its own standalone single candidate — most
        // commonly the neighbour half, discovered on an earlier sweep while this half's chunk was
        // still unloaded. Drop any such stale candidate from the list as we replace its map
        // entries, so `candidates` and `byPackedPos` never disagree about what exists.
        discardOrphan(session, packedPos, candidate);
        discardOrphan(session, packedSecondary, candidate);

        session.candidates.add(candidate);
        session.byPackedPos.put(packedPos, candidate);
        session.byPackedPos.put(packedSecondary, candidate);
    }

    /**
     * Attempts to pair {@code pos} with its double-chest neighbour, returning the canonicalised
     * candidate on success, or {@code null} if the neighbour doesn't validate as the other half.
     * Never throws: any inconsistency in the world state (unloaded neighbour, mismatched
     * block/type) is treated as "not confirmed as a double chest yet".
     * <p>
     * Pure with respect to {@link ScanSession} — it only decides what the candidate is, never
     * registers it. {@link #candidateAt} calls it directly, which is what lets a caller with no
     * session at all (the passive observer) ask the same pairing question the sweep does; {@link
     * #registerContainer} only ever reaches it through {@link #candidateAt}, and does the
     * {@code session.candidates} / {@code session.byPackedPos} bookkeeping itself once a candidate
     * comes back.
     */
    private ContainerCandidate tryPairDoubleChest(ClientLevel level, BlockPos pos, BlockState state, Block block,
                                                   ChestType type, ContainerKind kind) {
        Direction direction = ChestBlock.getConnectedDirection(state);
        BlockPos otherPos = pos.relative(direction);
        BlockState otherState = level.getBlockState(otherPos);

        if (otherState.getBlock() != block
                || !otherState.hasProperty(ChestBlock.TYPE)
                || otherState.getValue(ChestBlock.TYPE) != type.getOpposite()) {
            return null;
        }

        int[] a = toArray(pos);
        int[] b = toArray(otherPos);
        int[] canonical = DoubleChestPairing.canonical(a, b);
        int[] other = DoubleChestPairing.other(a, b);
        return new ContainerCandidate(canonical, other, kind.asDouble());
    }

    /**
     * If {@code packedPos} currently maps to a candidate other than {@code replacement}, removes
     * that candidate from {@code session.candidates} by identity (not position —
     * {@link ContainerCandidate} has no {@code equals}/{@code hashCode} override, so
     * {@link java.util.List#remove(Object)} already compares by reference), and removes
     * <em>every</em> {@code byPackedPos} key that pointed at it.
     * <p>
     * <b>Every key, not just this one.</b> The discarded candidate may itself be a double chest
     * whose other position is not one of the two the caller is about to re-register — a 2×2 of
     * chests re-pairing along the other axis after a rebuild does exactly that. Leaving that key
     * behind would keep it pointing at a candidate no longer in {@code candidates}, and
     * {@code scanChunk}'s {@code byPackedPos.containsKey} short-circuit would then skip that
     * position on every future sweep: the chest becomes permanently invisible to the scan, with
     * nothing in the counts to say so. The invariant this restores is the one stated on
     * {@link #registerContainer}: every position in {@code byPackedPos} maps to a candidate that
     * is actually in {@code candidates}.
     */
    private static void discardOrphan(ScanSession session, long packedPos, ContainerCandidate replacement) {
        ContainerCandidate existing = session.byPackedPos.get(packedPos);
        if (existing == null || existing == replacement) {
            return;
        }
        session.candidates.remove(existing);
        session.byPackedPos.values().removeIf(candidate -> candidate == existing);
    }

    /**
     * Classifies a block state as a scannable container kind, or {@code null} if it either isn't
     * a container this mod knows about or its kind is disabled in config.
     * <p>
     * Order matters: {@link TrappedChestBlock} extends {@link ChestBlock}, so it must be tested
     * before the plain {@link ChestBlock} check or every trapped chest would be misclassified.
     * <p>
     * Public because {@code ScanController} re-runs exactly this classification against the live
     * block state immediately before sending an interaction: a container discovered minutes ago may
     * have been broken since, and that must be decided with the same rules (config included) that
     * discovered it, not a second copy of them.
     */
    public ContainerKind kindOf(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof TrappedChestBlock) {
            return config.scanTrappedChests ? ContainerKind.TRAPPED_CHEST : null;
        }
        if (block instanceof ChestBlock) {
            return config.scanChests ? ContainerKind.CHEST : null;
        }
        if (block instanceof BarrelBlock) {
            return config.scanBarrels ? ContainerKind.BARREL : null;
        }
        if (block instanceof ShulkerBoxBlock) {
            return config.scanShulkerBoxes ? ContainerKind.SHULKER_BOX : null;
        }
        return null;
    }

    private static int[] toArray(BlockPos pos) {
        return new int[]{pos.getX(), pos.getY(), pos.getZ()};
    }
}
