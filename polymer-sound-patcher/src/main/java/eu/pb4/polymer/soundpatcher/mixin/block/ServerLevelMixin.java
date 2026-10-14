package eu.pb4.polymer.soundpatcher.mixin.block;

import eu.pb4.polymer.soundpatcher.impl.CoreBridge;
import eu.pb4.polymer.soundpatcher.impl.SoundRemapperImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Function;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Unique
    private final RandomSource soundSeedGenerator = RandomSource.createThreadSafe();

    @Shadow
    public abstract List<ServerPlayer> players();

    @Inject(method = "levelEvent", at = @At("TAIL"))
    private void handleSoundEvents(Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (type == LevelEvent.PARTICLES_DESTROY_BLOCK) {
            var state = Block.stateById(data);
            this.sendSoundToPlayersIfNeeded(state, pos, SoundType::getBreakSound, 0.5f, 0.8f);
        }
    }

    @Unique
    private void sendSoundToPlayersIfNeeded(BlockState state, BlockPos pos, Function<SoundType, SoundEvent> soundGetter, float volume, float pitch) {
        var group = state.getSoundType();

        Packet<?> sound = null;

        for (var player : this.players()) {
            double xd = pos.getX() - player.getX();
            double yd = pos.getY() - player.getY();
            double zd = pos.getZ() - player.getZ();
            if (xd * xd + yd * yd + zd * zd < 64 * 64) {
                if (SoundRemapperImpl.SOUND_EXCEPTION_IGNORER.contains(soundGetter.apply(CoreBridge.getClientSideSoundGroup(state, player)).location())) {
                    if (sound == null) {
                        sound = new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(soundGetter.apply(group)), SoundSource.BLOCKS,
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                                (group.getVolume() + 1.0f) * volume, group.getPitch() * pitch,
                                this.soundSeedGenerator.nextLong()
                        );
                    }

                    player.connection.send(sound);
                }
            }
        }
    }
}
