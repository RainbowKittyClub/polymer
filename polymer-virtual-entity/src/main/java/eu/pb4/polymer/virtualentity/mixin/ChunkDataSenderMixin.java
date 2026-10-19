package eu.pb4.polymer.virtualentity.mixin;

import eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;

// Leaving a chunk is handled in ServerChunkLoadingManagerMixin, once the player's new view is set.
@Mixin(PlayerChunkSender.class)
public class ChunkDataSenderMixin {
    @Inject(method = "sendChunk", at = @At("TAIL"), require = 0)
    private static void polymerVE$addToHolograms(ServerGamePacketListenerImpl handler, ServerLevel world, LevelChunk chunk, CallbackInfo ci) {
        for (var hologram : ((HolderAttachmentHolder) chunk).polymerVE$getHolders()) {
            hologram.startWatching(handler);
        }
    }
}
