package eu.pb4.polymer.core.mixin.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import eu.pb4.polymer.core.impl.other.ClientBlockClick;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    // The place sound leaves the placer out exactly when their client played its own; no lift may change that.
    @WrapOperation(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void polymer$followClientPlaceSound(Level level, Entity source, BlockPos pos, SoundEvent sound, SoundSource category, float volume, float pitch, Operation<Void> original, @Local(argsOnly = true) BlockPlaceContext context) {
        var click = ClientBlockClick.current();
        if (click == null) {
            original.call(level, source, pos, sound, category, volume, pitch);
            return;
        }
        ClientBlockClick.markPlaced(pos);
        try (var ignored = ClientBlockClick.finalExclusion()) {
            original.call(level, click.heardOwnSound() ? context.getPlayer() : null, pos, sound, category, volume, pitch);
        }
    }
}
