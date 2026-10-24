package eu.pb4.polymer.core.mixin.block;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.core.impl.PolymerImplUtils;
import eu.pb4.polymer.core.impl.interfaces.PolymerIdMapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.IdMapper;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public class BlockMixin {
    @Shadow
    @Final
    public static IdMapper<BlockState> BLOCK_STATE_REGISTRY;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void polymer$enableMapping(CallbackInfo ci) {
        ((PolymerIdMapper<BlockState>) BLOCK_STATE_REGISTRY).polymer$setChecker(
                x -> PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, x.getBlock()) != null,
                x -> PolymerImplUtils.isServerSideSyncableEntry((Registry<Object>) (Object) BuiltInRegistries.BLOCK, x.getBlock()),
                x -> "(Block) " + BuiltInRegistries.BLOCK.getKey(x.getBlock())
        );
    }

    @Inject(method = "spawnDestroyByEntityParticles", at = @At("HEAD"))
    private void addPolymerParticles(Level level, Entity entity, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (entity instanceof ServerPlayer serverPlayer && PolymerBlockUtils.shouldMineServerSide(serverPlayer, pos, state)) {
            if (!PolymerBlockUtils.isBrokenByClient(serverPlayer, pos)) {
                // Vanilla's break event leaves the breaker out, and its client played nothing itself
                serverPlayer.connection.send(new ClientboundLevelEventPacket(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(state), false));
            } else {
                var context = serverPlayer.connection.getPacketContext();
                var breakState = PolymerBlockUtils.getBreakEventBlockState(state, context);
                if (breakState != state) {
                    polymer$addBreakEffect(serverPlayer, pos, state, PolymerBlockUtils.getPolymerBlockState(state, context),
                            PolymerBlockUtils.getPolymerBlockState(breakState, context));
                }
            }
        }
    }

    // Its client played the break of the block it sees: add ours when that differs, with the sound only if its own
    // sounded different (a stand-in such as light sounds like stone).
    @Unique
    private static void polymer$addBreakEffect(ServerPlayer player, BlockPos pos, BlockState state, BlockState shown, BlockState breakShown) {
        if (breakShown == shown) {
            return;
        }
        // By id, as two sound types can hold separate SoundEvent instances of one sound
        var event = breakShown.getSoundType().getBreakSound().location().equals(shown.getSoundType().getBreakSound().location())
                ? LevelEvent.PARTICLES_DESTROY_BLOCK : LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK;
        player.connection.send(new ClientboundLevelEventPacket(event, pos, Block.getId(state), false));
    }
}
