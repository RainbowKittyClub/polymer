package eu.pb4.polymer.common.api;

import eu.pb4.polymer.common.impl.BulkChunkAccess;
import eu.pb4.polymer.common.impl.BulkSectionAccess;
import eu.pb4.polymer.common.mixin.BulkPlayerChunkSenderAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owner-thread direct-section reconciliation. Does not authorize writes or run vanilla maintenance. */
public final class PolymerBulkEdits {
    public static final String ENTRYPOINT = "polymer:bulk_edits";

    private PolymerBulkEdits() {
    }

    /**
     * Captures at most sixteen loaded sections immediately before a non-yielding native write.
     * Section numbers are absolute Y coordinates, not array indexes. Proposed states only inform
     * pre-mutation holder decisions; reconciliation independently reads committed states and NBT.
     * The caller owns tickets, exclusive mutation, lighting, vanilla metadata and callback safety.
     * Snapshotting serializes block-entity NBT per reserved cell and runs three full passes per
     * token (prepare, reconcile, publication revalidation); reserve sections accordingly.
     */
    public static Token prepare(LevelChunk chunk, Set<Integer> sections, Map<BlockPos, BlockState> proposed) {
        var level = owner(chunk);
        var access = (BulkChunkAccess) chunk;
        if (access.polymer$bulkToken() != null || access.polymer$bulkQuarantined()) {
            throw new IllegalStateException("Chunk already reserved or quarantined");
        }
        if (sections.isEmpty() || sections.size() > 16) {
            throw new IllegalArgumentException("Expected 1..16 sections");
        }
        for (int section : sections) {
            if (section < chunk.getMinSectionY() || section > chunk.getMaxSectionY()) {
                throw new IllegalArgumentException("Section outside chunk height: " + section);
            }
        }
        var positions = new LinkedHashMap<BlockPos, BlockState>();
        proposed.forEach((pos, state) -> {
            if ((pos.getX() >> 4) != chunk.getPos().x() || (pos.getZ() >> 4) != chunk.getPos().z()
                    || !sections.contains(pos.getY() >> 4)) {
                throw new IllegalArgumentException("Proposal outside reserved sections: " + pos);
            }
            positions.put(pos.immutable(), Objects.requireNonNull(state));
        });
        var token = new Token(chunk, level, Set.copyOf(sections), Map.copyOf(positions));
        access.polymer$bulkToken(token);
        try {
            for (var participant : FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, Participant.class)) {
                token.sessions.add(Objects.requireNonNull(participant.prepare(chunk, token.sections, token.proposed)));
            }
            return token;
        } catch (RuntimeException | Error error) {
            token.fail(error);
            throw error;
        }
    }

    /** Independent module/consumer hook. Presence is not a declaration that arbitrary callbacks are safe. */
    public interface Participant {
        /** Capture decisions without mutating the world, holders or block entities. */
        Session prepare(LevelChunk chunk, Set<Integer> sections, Map<BlockPos, BlockState> proposed);
    }

    /** Per-token participant state. Cleanup is required even after another participant fails. */
    public interface Session {
        /** Rebuild complete section metadata before any lifecycle callback runs. */
        default void rebuild() { }
        /** Reconcile only these independently observed changes. */
        void reconcile(List<Change> changes);
        /** Release deferred watcher updates after recipient-aware block/light publication. */
        default void publish() { }
        /** Cleanup without replay. Failed mutations may leave this loaded chunk quarantined. */
        default void abort() { }
    }

    /** Immutable cell delta; NBT accessors return defensive copies. */
    public record Change(BlockPos pos, BlockState before, BlockState after,
                         CompoundTag beforeNbt, CompoundTag afterNbt) {
        public Change {
            pos = pos.immutable();
            beforeNbt = copy(beforeNbt);
            afterNbt = copy(afterNbt);
        }

        @Override
        public CompoundTag beforeNbt() { return copy(this.beforeNbt); }

        @Override
        public CompoundTag afterNbt() { return copy(this.afterNbt); }

        /** Includes block-entity-only writes. */
        public boolean nbtChanged() { return !Objects.equals(this.beforeNbt, this.afterNbt); }
    }

    /** Single-use loaded-chunk token; repeated successful reconciliation/publication is idempotent. */
    public static final class Token implements AutoCloseable {
        private final LevelChunk chunk;
        private final ServerLevel level;
        private final Set<Integer> sections;
        private final Map<BlockPos, BlockState> proposed;
        private final Map<BlockPos, Cell> before;
        private final List<Session> sessions = new ArrayList<>();
        private final Set<ServerGamePacketListenerImpl> deferred = new LinkedHashSet<>();
        private final long tick;
        private Map<BlockPos, Cell> after;
        private Map<Integer, SectionStamp> sectionStamps;
        private List<Change> changes = List.of();
        private Set<BlockPos> changedPositions = Set.of();
        private Phase phase = Phase.PREPARED;
        private boolean sending;
        private boolean cleaned;
        private boolean lifecycleChanged;

        private Token(LevelChunk chunk, ServerLevel level, Set<Integer> sections,
                      Map<BlockPos, BlockState> proposed) {
            this.chunk = chunk;
            this.level = level;
            this.sections = sections;
            this.proposed = proposed;
            this.before = snapshot(chunk, sections);
            this.tick = level.getServer().getTickCount();
        }

        /** Reads actual committed deltas, even after partial mutation; never applies a proposal. */
        public List<Change> reconcile() {
            checkOwner();
            if (this.phase == Phase.RECONCILED || this.phase == Phase.PUBLISHED) {
                return this.changes;
            }
            require(Phase.PREPARED);
            try {
                if (this.level.getServer().getTickCount() != this.tick) {
                    throw new IllegalStateException("Preparation and mutation must not span server ticks");
                }
                this.after = snapshot(this.chunk, this.sections);
                this.changes = differences(this.before, this.after);
                this.changedPositions = this.changes.stream().map(Change::pos)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                // Repair all section indexes even if a holder or consumer later refuses a final state.
                for (var session : this.sessions) {
                    session.rebuild();
                }
                for (var change : this.changes) {
                    if (change.before != change.after && this.proposed.get(change.pos) != change.after) {
                        throw new IllegalStateException("Unplanned committed state at " + change.pos);
                    }
                }
                this.lifecycleChanged = !this.changes.isEmpty();
                for (var session : this.sessions) {
                    session.reconcile(this.changes);
                }
                this.sectionStamps = stamps(this.chunk, this.sections);
                this.phase = Phase.RECONCILED;
                return this.changes;
            } catch (RuntimeException | Error error) {
                fail(error);
                throw error;
            }
        }

        /**
         * Called after awaited lighting. Revalidates ownership and sends separately through vanilla's
         * PlayerChunkSender (and Polymer's PacketContext), then releases new holders. No packet is shared.
         */
        public void publish() {
            checkOwner();
            if (this.phase == Phase.PUBLISHED) {
                return;
            }
            require(Phase.RECONCILED);
            try {
                // Reserved-scope revalidation only: section identity/revision catches in-place and
                // ABA state writes; the final state/NBT snapshot catches block-entity lifecycle and
                // payload changes. Chunk-wide block-entity setChanged bumps (e.g. a furnace ticking
                // anywhere in the chunk during the caller's awaited lighting) are outside the owned
                // scope and must not fail publication.
                if (!this.sectionStamps.equals(stamps(this.chunk, this.sections))
                        || !this.after.equals(snapshot(this.chunk, this.sections))) {
                    throw new IllegalStateException("Chunk changed while awaiting publication");
                }
                var recipients = new LinkedHashSet<>(this.deferred);
                for (var player : this.level.getChunkSource().chunkMap.getPlayers(this.chunk.getPos(), false)) {
                    recipients.add(player.connection);
                }
                this.sending = true;
                try {
                    for (var connection : recipients) {
                        if (connection.isAcceptingMessages() && connection.player.level() == this.level
                                && connection.player.getChunkTrackingView().contains(this.chunk.getPos().x(), this.chunk.getPos().z())) {
                            BulkPlayerChunkSenderAccessor.polymer$send(connection, this.level, this.chunk);
                        }
                    }
                } finally {
                    this.sending = false;
                }
                this.phase = Phase.PUBLISHING;
                for (var session : this.sessions) {
                    session.publish();
                }
                this.phase = Phase.PUBLISHED;
                release();
            } catch (RuntimeException | Error error) {
                fail(error);
                throw error;
            }
        }

        /** Last observed actual effects remain available after a failed reconciliation. */
        public List<Change> changes() {
            checkThread(this.level);
            return this.changes;
        }

        /** Used by the normal chunk sender while lighting is pending, without thread-local state. */
        public boolean defer(ServerGamePacketListenerImpl connection) {
            checkThread(this.level);
            if (this.sending) {
                return false;
            }
            this.deferred.add(connection);
            return true;
        }

        /** New holder watchers remain gated until all recipients have their chunk and light data. */
        public boolean defersWatchers() {
            return this.phase != Phase.PUBLISHING && this.phase != Phase.PUBLISHED;
        }

        /** Pauses only affected holders while their block/light publication is pending. */
        public boolean defersPosition(BlockPos pos) {
            return defersWatchers() && (this.phase == Phase.PREPARED
                    ? this.proposed.containsKey(pos) : this.changedPositions.contains(pos));
        }

        /** A normal mutation cannot run both normal and bulk holder transitions. */
        public void ordinaryWrite() {
            checkThread(this.level);
            fail(new IllegalStateException("Ordinary write overlaps a direct-section edit"));
        }

        /** Cancel before mutation, or fail closed after mutation. Never rolls back or retries writes. */
        @Override
        public void close() {
            checkThread(this.level);
            if (this.phase == Phase.PUBLISHED || this.cleaned) {
                return;
            }
            fail(null);
        }

        private void checkOwner() {
            checkThread(this.level);
            if (owner(this.chunk) != this.level) {
                throw new IllegalStateException("Chunk identity changed");
            }
            if (this.phase != Phase.PUBLISHED && ((BulkChunkAccess) this.chunk).polymer$bulkToken() != this) {
                throw new IllegalStateException("Token no longer owns chunk");
            }
        }

        private void require(Phase expected) {
            if (this.phase != expected) {
                throw new IllegalStateException("Expected " + expected + ", got " + this.phase);
            }
        }

        private void release() {
            ((BulkChunkAccess) this.chunk).polymer$bulkToken(null);
            this.deferred.clear();
        }

        private void fail(Throwable cause) {
            if (this.cleaned) {
                return;
            }
            this.cleaned = true;
            this.phase = Phase.FAILED;
            Throwable failure = cause;
            boolean mutated = true;
            try {
                this.changes = differences(this.before, snapshot(this.chunk, this.sections));
                mutated = this.lifecycleChanged || !this.changes.isEmpty();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            for (var session : this.sessions.reversed()) {
                try {
                    session.abort();
                } catch (RuntimeException | Error error) {
                    mutated = true;
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (mutated) {
                ((BulkChunkAccess) this.chunk).polymer$bulkQuarantine();
                var recipients = new LinkedHashSet<>(this.deferred);
                for (var player : this.level.getChunkSource().chunkMap.getPlayers(this.chunk.getPos(), false)) {
                    recipients.add(player.connection);
                }
                for (var connection : recipients) {
                    if (connection.isAcceptingMessages()) {
                        connection.disconnect(Component.literal("Bulk edit finalization failed; chunk requires reload"));
                    }
                }
            }
            release();
            if (cause == null && failure != null) {
                throw new IllegalStateException("Bulk edit cleanup failed", failure);
            }
        }
    }

    private enum Phase { PREPARED, RECONCILED, PUBLISHING, PUBLISHED, FAILED }

    private record Cell(BlockState state, CompoundTag nbt) { }

    private record SectionStamp(Object section, long revision) { }

    private static Map<Integer, SectionStamp> stamps(LevelChunk chunk, Set<Integer> sections) {
        var result = new LinkedHashMap<Integer, SectionStamp>();
        for (int y : sections) {
            var section = chunk.getSection(chunk.getSectionIndexFromSectionY(y));
            result.put(y, new SectionStamp(section, ((BulkSectionAccess) section).polymer$bulkSectionRevision()));
        }
        return Map.copyOf(result);
    }

    private static ServerLevel owner(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Server chunk required");
        }
        checkThread(level);
        if (!((BulkChunkAccess) chunk).polymer$bulkLoaded()
                || level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z()) != chunk) {
            throw new IllegalStateException("Chunk is not the current loaded instance");
        }
        return level;
    }

    private static void checkThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Bulk edits require the server thread");
        }
    }

    private static CompoundTag copy(CompoundTag nbt) {
        return nbt == null ? null : nbt.copy();
    }

    private static Map<BlockPos, Cell> snapshot(LevelChunk chunk, Set<Integer> sections) {
        var result = new LinkedHashMap<BlockPos, Cell>();
        for (int section : sections) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        var pos = chunk.getPos().getBlockAt(x, section * 16 + y, z);
                        var state = chunk.getBlockState(pos);
                        var nbt = state.hasBlockEntity()
                                ? chunk.getBlockEntityNbtForSaving(pos, chunk.getLevel().registryAccess()) : null;
                        result.put(pos, new Cell(state, copy(nbt)));
                    }
                }
            }
        }
        return result;
    }

    private static List<Change> differences(Map<BlockPos, Cell> before, Map<BlockPos, Cell> after) {
        var result = new ArrayList<Change>();
        before.forEach((pos, old) -> {
            var current = after.get(pos);
            if (!old.equals(current)) {
                result.add(new Change(pos, old.state, current.state, old.nbt, current.nbt));
            }
        });
        return List.copyOf(result);
    }
}
