package eu.pb4.polymer.virtualentity.mixin.block;

import eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder;
import eu.pb4.polymer.virtualentity.impl.HolderHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ChunkMap.class)
public abstract class ServerChunkLoadingManagerMixin {
    @Shadow
    @Final
    private ServerLevel level;

    // Vanilla drops chunks before it installs the new view, so tracking checks made while dropping
    // still see the old one; the view in effect before applyChunkTrackingView, for its TAIL.
    @Unique
    private @Nullable ChunkTrackingView polymerVE$previousView;

    @Inject(method = "updatePlayerStatus", at = @At("TAIL"))
    private void polymerVE$clearHolograms(ServerPlayer player, boolean added, CallbackInfo ci) {
        if (!added) {
            var holders = ((HolderHolder) player.connection).polymer$getHolders();
            if (!holders.isEmpty()) {
                var arr = holders.toArray(HolderHolder.ELEMENT_HOLDERS);
                for (int i = 0; i < arr.length; i++) {
                    var holder = arr[i];
                    if (holder.getAttachment() != null) {
                        holder.getAttachment().updateTracking(player.connection);
                    }
                }
            }
        }
    }

    @Inject(method = "applyChunkTrackingView", at = @At("HEAD"))
    private void polymerVE$rememberView(ServerPlayer player, ChunkTrackingView next, CallbackInfo ci) {
        this.polymerVE$previousView = player.getChunkTrackingView();
    }

    // With the new view in place, each chunk the player left asks its own attachments to re-check,
    // so they stop watching even when the chunk itself stays loaded.
    @Inject(method = "applyChunkTrackingView", at = @At("TAIL"))
    private void polymerVE$unwatchDroppedChunks(ServerPlayer player, ChunkTrackingView next, CallbackInfo ci) {
        var previous = this.polymerVE$previousView;
        this.polymerVE$previousView = null;
        if (previous == null || player.level() != this.level) {
            return;
        }
        ChunkTrackingView.difference(previous, next, pos -> {}, pos -> {
            var chunk = this.level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) {
                return;
            }
            var attachments = ((HolderAttachmentHolder) chunk).polymerVE$getHolders();
            if (attachments.isEmpty()) {
                return;
            }
            for (var attachment : attachments.toArray(HolderHolder.HOLDER_ATTACHMENTS)) {
                if (attachment.holder().getWatchingPlayers().contains(player.connection)) {
                    attachment.updateTracking(player.connection);
                }
            }
        });
    }
}
