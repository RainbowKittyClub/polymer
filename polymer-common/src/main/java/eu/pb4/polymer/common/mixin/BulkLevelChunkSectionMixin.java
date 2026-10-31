package eu.pb4.polymer.common.mixin;

import eu.pb4.polymer.common.impl.BulkSectionAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
public class BulkLevelChunkSectionMixin implements BulkSectionAccess {
    @Unique private long polymer$bulkRevision;

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"))
    private void polymer$write(int x, int y, int z, BlockState state, boolean checked,
                               CallbackInfoReturnable<BlockState> ci) {
        this.polymer$bulkRevision++;
    }

    @Override
    public long polymer$bulkSectionRevision() {
        return this.polymer$bulkRevision;
    }
}
