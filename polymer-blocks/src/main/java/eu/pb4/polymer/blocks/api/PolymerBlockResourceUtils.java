package eu.pb4.polymer.blocks.api;

import eu.pb4.polymer.blocks.impl.BlockExtBlockMapper;
import eu.pb4.polymer.blocks.impl.PolymerBlocksInternal;
import eu.pb4.polymer.common.impl.CompatStatus;
import eu.pb4.polymer.core.api.block.BlockMapper;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

public final class PolymerBlockResourceUtils {
    private PolymerBlockResourceUtils() {}

    final static BlockResourceCreator CREATOR = new BlockResourceCreator(PolymerResourcePackUtils.getInstance(), BlockExtBlockMapper.INSTANCE, () -> {
        BlockMapper.DEFAULT_MAPPER_EVENT.register((player, mapper) -> BlockExtBlockMapper.INSTANCE);
    });

    @Nullable
    public static synchronized BlockState requestBlock(BlockModelType type, PolymerBlockModel model) {
        return requestBlock(type, BlockResourceCreator.ANY_STATE, model);
    }

    public static synchronized BlockState requestEmpty(BlockModelType type) {
        var empty = CREATOR.requestEmpty(type);
        if (empty == null) {
            throw new IllegalStateException("Empty state should never be null! BlockModelType: " + type);
        }
        return empty;
    }

    @Nullable
    public static synchronized BlockState requestBlock(BlockModelType type, PolymerBlockModel... model) {
        return requestBlock(type, BlockResourceCreator.ANY_STATE, model);
    }

    @Nullable
    public static synchronized BlockState requestBlock(BlockModelType type, MultiPolymerBlockModel model) {
        return requestBlock(type, BlockResourceCreator.ANY_STATE, model);
    }

    /**
     * Same as {@link #requestBlock(BlockModelType, PolymerBlockModel...)}, but only takes a state
     * the predicate accepts.
     */
    @Nullable
    public static synchronized BlockState requestBlock(BlockModelType type, Predicate<BlockState> predicate, PolymerBlockModel... model) {
        if (!CREATOR.hasRequestedEmpty(type) && CREATOR.getBlocksLeft(type) <= 1) {
            return null;
        }
        return CREATOR.requestBlock(type, predicate, model);
    }

    @Nullable
    public static synchronized BlockState requestBlock(BlockModelType type, Predicate<BlockState> predicate, MultiPolymerBlockModel model) {
        if (!CREATOR.hasRequestedEmpty(type) && CREATOR.getBlocksLeft(type) <= 1) {
            return null;
        }
        return CREATOR.requestBlock(type, predicate, model);
    }

    /**
     * Number of slots {@link #requestStableBlock(int, PolymerBlockModel...)} accepts.
     */
    public static synchronized int getStableSlotCount() {
        return CREATOR.getStableSlotCount();
    }

    /**
     * Requests a full block whose client-side state keeps its model through a vanilla client's own
     * block updates while the block below is wood, air or stone, for blocks seen and broken in large
     * numbers, like logs and terrain.
     * The state depends only on the slot, so callers should keep a fixed list of which block gets
     * which slot. There are few slots; fall back to {@link #requestBlock(BlockModelType, PolymerBlockModel...)}.
     *
     * @param slot from 0 to {@link #getStableSlotCount()} - 1
     * @return the state, or null when the slot is taken or out of range
     */
    @Nullable
    public static synchronized BlockState requestStableBlock(int slot, PolymerBlockModel... model) {
        return CREATOR.requestStableBlock(slot, model);
    }

    @Nullable
    public static synchronized BlockState requestStableBlock(int slot, MultiPolymerBlockModel model) {
        return CREATOR.requestStableBlock(slot, model);
    }

    public static synchronized int getBlocksLeft(BlockModelType type) {
        if (CREATOR.hasRequestedEmpty(type)) {
            return CREATOR.getBlocksLeft(type);
        }

        return Math.max(CREATOR.getBlocksLeft(type) - 1, 0);
    }
}
