package eu.pb4.polymer.core.mixin.block.packet;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

@Mixin(ClientboundLevelEventPacket.class)
public class ClientboundLevelEventPacketMixin {
    @Shadow @Final private int type;
    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;writeInt(I)Lnet/minecraft/network/FriendlyByteBuf;", ordinal = 1))
    private int polymer$replaceValue(int data) {
        if (this.type == LevelEvent.PARTICLES_DESTROY_BLOCK
                || this.type == LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK
        ) {
            var player = PacketContext.get();
            var state = PolymerBlockUtils.getBreakEventBlockState(Block.stateById(data), player);
            return Block.getId(PolymerBlockUtils.getPolymerBlockState(state, player));
        }

        return data;
    }
}
