package eu.pb4.polymer.common.mixin;

import eu.pb4.polymer.common.api.PolymerBulkEdits;
import eu.pb4.polymer.common.impl.BulkChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public class BulkLevelChunkMixin implements BulkChunkAccess {
    @Shadow private boolean loaded;
    @Unique private long polymer$bulkRevision;
    @Unique private PolymerBulkEdits.Token polymer$bulkToken;
    @Unique private boolean polymer$bulkQuarantined;

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void polymer$write(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> ci) {
        if (this.polymer$bulkToken != null) {
            this.polymer$bulkToken.ordinaryWrite();
        }
        this.polymer$bulkRevision++;
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"))
    private void polymer$entity(BlockEntity entity, CallbackInfo ci) {
        this.polymer$bulkRevision++;
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void polymer$remove(BlockPos pos, CallbackInfo ci) {
        this.polymer$bulkRevision++;
    }

    @Inject(method = "setLoaded", at = @At("HEAD"))
    private void polymer$unload(boolean loaded, CallbackInfo ci) {
        if (!loaded && this.polymer$bulkToken != null) {
            this.polymer$bulkToken.close();
        }
    }

    @Override public boolean polymer$bulkLoaded() { return this.loaded; }
    @Override public long polymer$bulkRevision() { return this.polymer$bulkRevision; }
    @Override public void polymer$bulkChanged() { this.polymer$bulkRevision++; }
    @Override public PolymerBulkEdits.Token polymer$bulkToken() { return this.polymer$bulkToken; }
    @Override public void polymer$bulkToken(PolymerBulkEdits.Token token) { this.polymer$bulkToken = token; }
    @Override public boolean polymer$bulkQuarantined() { return this.polymer$bulkQuarantined; }
    @Override public void polymer$bulkQuarantine() { this.polymer$bulkQuarantined = true; }
}
