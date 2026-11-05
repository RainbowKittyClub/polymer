package eu.pb4.polymer.core.impl;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.impl.interfaces.PolymerChunkSectionStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Shared normal-write and replacement-section synchronization criteria. */
public final class PolymerSectionIndex {
    private PolymerSectionIndex() {
    }

    /** Updates one cell, including globally mapped vanilla states. */
    public static void update(LevelChunkSection section, int x, int y, int z, BlockState state) {
        var storage = (PolymerChunkSectionStorage) section;
        if (PolymerImplUtils.POLYMER_STATES.contains(state)) {
            storage.polymer$setSynced(x, y, z, PolymerBlockUtils.forceLightUpdates(state),
                    PolymerBlockUtils.forceLightInsideBlock(state));
        } else {
            storage.polymer$removeSynced(x, y, z);
        }
    }

    /** Rebuilds every cell, retaining light invalidation from the replaced section. */
    public static void rebuild(LevelChunkSection section, boolean previousLight) {
        var storage = (PolymerChunkSectionStorage) section;
        storage.polymer$clear();
        if (previousLight) {
            storage.polymer$setRequireLights(true);
        }
        if (!section.getStates().maybeHas(PolymerImplUtils.POLYMER_STATES::contains)) {
            return;
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    update(section, x, y, z, section.getBlockState(x, y, z));
                }
            }
        }
    }
}
