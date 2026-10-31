package eu.pb4.polymer.virtualentity.impl;

import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Shared normal/bulk block-bound holder lifecycle. Decisions precede block-entity replacement. */
public final class BlockHolderLifecycle {
    private BlockHolderLifecycle() {
    }

    /** Captures the old attachment and preservation decision without changing it. */
    public static Transition prepare(LevelChunk chunk, BlockPos pos, BlockState state) {
        var attachment = BlockBoundAttachment.get(chunk, pos);
        var creator = BlockWithElementHolder.get(state);
        boolean keep = false;
        if (attachment != null && attachment.holder().getAttachment() == attachment && !attachment.isRemoved()) {
            boolean sameBlock = attachment.getBlockState().getBlock() == state.getBlock();
            if (creator == null) {
                keep = sameBlock;
            } else if (chunk.getLevel() instanceof ServerLevel world) {
                keep = attachment.shouldTick() == creator.tickElementHolder(world, pos, state)
                        && attachment.getPos().equals(Vec3.atCenterOf(pos).add(creator.getElementHolderOffset(world, pos, state)))
                        && (sameBlock || creator.keepElementHolder(world, pos, attachment.getBlockState(), state, attachment.holder()));
            }
        }
        return new Transition(pos.immutable(), state, attachment, keep);
    }

    /** Applies the captured transition once. Bulk callers defer the retained holder's callback. */
    public static void apply(LevelChunk chunk, Transition transition, boolean notify) {
        var current = BlockBoundAttachment.get(chunk, transition.pos);
        if (current != transition.attachment) {
            throw new IllegalStateException("Holder changed since preparation at " + transition.pos);
        }
        if (current != null) {
            if (transition.keep && current.holder().getAttachment() == current && !current.isRemoved()) {
                current.setBlockState(transition.state, notify);
            } else {
                ((HolderAttachmentHolder) chunk).polymerVE$removePosHolder(transition.pos);
            }
        }
    }

    /** Creates only a missing holder, after native block-entity maintenance. */
    public static void create(LevelChunk chunk, BlockPos pos, BlockState state) {
        if (BlockBoundAttachment.get(chunk, pos) == null && chunk.getLevel() instanceof ServerLevel world) {
            var creator = BlockWithElementHolder.get(state);
            if (creator != null) {
                var holder = creator.createElementHolder(world, pos, state);
                if (holder != null) {
                    if (((eu.pb4.polymer.common.impl.BulkChunkAccess) chunk).polymer$bulkToken() != null
                            && (holder.getAttachment() != null || !holder.getWatchingPlayers().isEmpty())) {
                        throw new IllegalStateException("Bulk holder factory must return a fresh unattached holder");
                    }
                    BlockBoundAttachment.of(holder, world, chunk, pos.immutable(), state);
                }
            }
        }
    }

    /** Pre-mutation lifecycle decision, bound to the exact attachment instance. */
    public record Transition(BlockPos pos, BlockState state, BlockBoundAttachment attachment, boolean keep) {
    }
}
