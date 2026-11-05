package eu.pb4.polymer.common.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerChunkSender.class)
public interface BulkPlayerChunkSenderAccessor {
    @Invoker("sendChunk")
    static void polymer$send(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk) {
        throw new UnsupportedOperationException();
    }
}
