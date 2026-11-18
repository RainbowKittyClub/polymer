package eu.pb4.polymer.resourcepack.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.impl.NoPackAdvancementBackgrounds;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * A client without the main resource pack has no texture for a modded advancement tab background
 * and draws the missing-texture checkerboard, so it is sent a vanilla texture instead: the one the
 * owning mod names in fabric.mod.json, else vanilla's stone background (see
 * {@link NoPackAdvancementBackgrounds}). Both reads of the field in the encoder are replaced, so
 * the presence flag stays in step.
 */
@Mixin(DisplayInfo.class)
public class DisplayInfoMixin {
    @ModifyExpressionValue(method = "serializeToNetwork", at = @At(value = "FIELD",
            target = "Lnet/minecraft/advancements/DisplayInfo;background:Ljava/util/Optional;",
            opcode = Opcodes.GETFIELD))
    private Optional<ClientAsset.ResourceTexture> polymer$vanillaBackgroundWithoutPack(
            Optional<ClientAsset.ResourceTexture> background) {
        if (background.isEmpty() || background.get().id().getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return background;
        }
        var context = PacketContext.get();
        if (context.get(PacketContext.CONNECTION) != null && !PolymerResourcePackUtils.hasMainPack(context)) {
            return Optional.of(new ClientAsset.ResourceTexture(
                    NoPackAdvancementBackgrounds.fallbackFor(background.get().id())));
        }
        return background;
    }
}
