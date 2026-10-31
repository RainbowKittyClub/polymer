package eu.pb4.polymer.virtualentity.impl;

import eu.pb4.polymer.common.api.PolymerBulkEdits;
import eu.pb4.polymer.common.impl.CompatStatus;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optional virtual-entity participant; no core dependency. */
public final class VirtualBulkEdits implements PolymerBulkEdits.Participant {
    @Override
    public PolymerBulkEdits.Session prepare(LevelChunk chunk, Set<Integer> sections,
                                            Map<BlockPos, BlockState> proposed) {
        if (CompatStatus.IMMERSIVE_PORTALS) {
            throw new UnsupportedOperationException("Bulk publication with Immersive Portals is not supported");
        }
        for (var attachment : ((HolderAttachmentHolder) chunk).polymerVE$getHolders()) {
            if (!(attachment instanceof BlockBoundAttachment bound)
                    || BlockWithElementHolder.get(bound.getBlockState()) == null) {
                throw new UnsupportedOperationException("Manual chunk attachments require an owner adapter");
            }
        }
        var transitions = new HashMap<BlockPos, BlockHolderLifecycle.Transition>();
        proposed.forEach((pos, state) -> transitions.put(pos, BlockHolderLifecycle.prepare(chunk, pos, state)));
        return new PolymerBulkEdits.Session() {
            private final List<PolymerBulkEdits.Change> touched = new ArrayList<>();

            @Override
            public void reconcile(List<PolymerBulkEdits.Change> changes) {
                for (var change : changes) {
                    this.touched.add(change);
                    if (change.before() != change.after()) {
                        var transition = transitions.get(change.pos());
                        if (transition == null || transition.state() != change.after()) {
                            throw new IllegalStateException("Missing pre-mutation holder decision at " + change.pos());
                        }
                        BlockHolderLifecycle.apply(chunk, transition, false);
                        BlockHolderLifecycle.create(chunk, change.pos(), change.after());
                    }
                }
            }

            @Override
            public void publish() {
                for (var change : this.touched) {
                    var attachment = BlockBoundAttachment.get(chunk, change.pos());
                    if (attachment != null) {
                        if (change.before() != change.after()) {
                            attachment.holder().notifyUpdate(BlockBoundAttachment.BLOCK_STATE_UPDATE);
                        }
                        if (change.nbtChanged()) {
                            attachment.holder().notifyUpdate(BlockBoundAttachment.BLOCK_ENTITY_UPDATE);
                        }
                    }
                }
                // Also admit watchers of untouched holders whose initial chunk send was deferred.
                for (var attachment : List.copyOf(((HolderAttachmentHolder) chunk).polymerVE$getHolders())) {
                    attachment.updateCurrentlyTracking(List.copyOf(attachment.holder().getWatchingPlayers()));
                }
            }

            @Override
            public void abort() {
                for (var change : this.touched) {
                    var attachment = BlockBoundAttachment.get(chunk, change.pos());
                    if (attachment != null) {
                        attachment.holder().destroy();
                    }
                }
            }
        };
    }
}
