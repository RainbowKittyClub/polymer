package eu.pb4.polymer.common.mixin;

import eu.pb4.polymer.common.impl.BulkChunkAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerChunkSender.class)
public class BulkPlayerChunkSenderMixin {
    @Inject(method = "sendChunk", at = @At("HEAD"), cancellable = true)
    private static void polymer$defer(ServerGamePacketListenerImpl connection, ServerLevel level,
                                      LevelChunk chunk, CallbackInfo ci) {
        var access = (BulkChunkAccess) chunk;
        if (access.polymer$bulkQuarantined()) {
            connection.disconnect(Component.literal("Bulk edit finalization failed; chunk requires reload"));
            ci.cancel();
        } else if (access.polymer$bulkToken() != null && access.polymer$bulkToken().defer(connection)) {
            ci.cancel();
        }
    }
}
