package eu.pb4.polymer.bulkclient;

import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Unattended pack choice for these disposable offline clients only. */
@Mixin(ServerData.class)
public class PackChoiceMixin {
    @Inject(method = "getResourcePackStatus", at = @At("HEAD"), cancellable = true)
    private void polymerTest$choice(CallbackInfoReturnable<ServerData.ServerPackStatus> cir) {
        cir.setReturnValue(Boolean.getBoolean("polymer.bulk.pack")
                ? ServerData.ServerPackStatus.ENABLED : ServerData.ServerPackStatus.DISABLED);
    }
}
