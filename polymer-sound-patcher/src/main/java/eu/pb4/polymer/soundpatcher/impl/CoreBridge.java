package eu.pb4.polymer.soundpatcher.impl;

import eu.pb4.polymer.common.impl.CompatStatus;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.item.PolymerBlockItemStandIns;
import eu.pb4.polymer.core.api.utils.PolymerUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

public class CoreBridge {
    // True while polymer-core fixed the entity a sound leaves out from the client's own prediction.
    public static boolean isSoundExclusionFinal() {
        return CompatStatus.POLYMER_CORE && PolymerUtils.isPlaySoundExclusionFinal();
    }

    // A converted sound reaches a client with the pack only from the server, so it never plays its own.
    public static void registerMutedPlaceSounds() {
        PolymerBlockItemStandIns.registerMutedSound((sound, context) ->
                SoundRemapperImpl.isConverted(sound) && PolymerResourcePackUtils.hasMainPack(context));
    }

    // True when this player's client mutes its own copy of the sound, so it hears only the server's.
    public static boolean isMutedFor(SoundEvent sound, Entity entity) {
        return CompatStatus.POLYMER_CORE && entity instanceof ServerPlayer player
                && PolymerBlockItemStandIns.isMuted(sound, player.connection.getPacketContext());
    }

    public static SoundType getClientSideSoundGroup(BlockState state, Entity entity) {
        if (CompatStatus.POLYMER_CORE && entity instanceof ServerPlayer player) {
            state = PolymerBlockUtils.getPolymerBlockState(state, player.connection.getPacketContext());
        }

        return state.getSoundType();
    }

    public static SoundType getClientSideSoundGroupBreaking(BlockState state, Entity entity) {
        if (CompatStatus.POLYMER_CORE && entity instanceof ServerPlayer player) {
            var context = player.connection.getPacketContext();
            state = PolymerBlockUtils.getPolymerBlockState(PolymerBlockUtils.getBreakEventBlockState(state, context), context);
        }

        return state.getSoundType();
    }
}
