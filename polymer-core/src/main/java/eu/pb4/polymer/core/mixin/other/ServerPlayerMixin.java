package eu.pb4.polymer.core.mixin.other;

import eu.pb4.polymer.core.impl.interfaces.GenericPlayerContext;
import eu.pb4.polymer.core.impl.other.SignEditSession;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    @Inject(method = "initMenu", at = @At("HEAD"))
    private void polymer$setPlayerContext(AbstractContainerMenu screenHandler, CallbackInfo ci) {
        if (screenHandler instanceof GenericPlayerContext context) {
            context.polymer$setPlayer((ServerPlayer) (Object) this);
        }
    }

    @Inject(method = "openTextEdit", at = @At("HEAD"), cancellable = true)
    private void polymer$openSignEditDialog(SignBlockEntity sign, SignTextSlot slot, CallbackInfo ci) {
        if (SignEditSession.open((ServerPlayer) (Object) this, sign, slot == SignTextSlot.FRONT)) {
            ci.cancel();
        }
    }
}
