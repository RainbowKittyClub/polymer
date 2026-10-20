package eu.pb4.polymer.soundpatcher.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import eu.pb4.polymer.soundpatcher.impl.CoreBridge;
import eu.pb4.polymer.soundpatcher.impl.SoundRemapperImpl;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {
    @ModifyVariable(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Entity ignoreEntityException(Entity entity, @Local(argsOnly = true) Holder<SoundEvent> soundEvent) {
        return polymer$lifts(entity, soundEvent.value()) ? null : entity;
    }

    @ModifyVariable(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Entity ignoreEntityException2(Entity entity, @Local(argsOnly = true) Holder<SoundEvent> soundEvent) {
        return polymer$lifts(entity, soundEvent.value()) ? null : entity;
    }

    // A final exclusion still lifts for a client that mutes its own copy, as that client heard nothing.
    @Unique
    private static boolean polymer$lifts(Entity entity, SoundEvent sound) {
        return SoundRemapperImpl.ignoreExceptions(sound) && (!CoreBridge.isSoundExclusionFinal() || CoreBridge.isMutedFor(sound, entity));
    }
}
