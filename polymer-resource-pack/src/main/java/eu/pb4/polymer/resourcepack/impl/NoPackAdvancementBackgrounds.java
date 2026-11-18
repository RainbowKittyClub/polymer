package eu.pb4.polymer.resourcepack.impl;

import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Vanilla textures sent in place of modded advancement tab backgrounds to clients without the main
 * resource pack. A mod picks its own in fabric.mod.json, for example
 * {@code "custom": {"polymer:no_pack_advancement_backgrounds": {"mymod:gui/tab": "minecraft:block/dark_prismarine"}}};
 * anything not listed gets vanilla's stone background.
 */
@ApiStatus.Internal
public final class NoPackAdvancementBackgrounds {
    public static final String CUSTOM_VALUE = "polymer:no_pack_advancement_backgrounds";
    private static final Logger LOGGER = LoggerFactory.getLogger(NoPackAdvancementBackgrounds.class);
    private static final Identifier DEFAULT = Identifier.withDefaultNamespace("gui/advancements/backgrounds/stone");
    private static final Map<Identifier, Identifier> FALLBACKS = new HashMap<>();

    private NoPackAdvancementBackgrounds() {
    }

    public static Identifier fallbackFor(Identifier background) {
        return FALLBACKS.getOrDefault(background, DEFAULT);
    }

    public static void readFrom(ModMetadata metadata) {
        var value = metadata.getCustomValue(CUSTOM_VALUE);
        if (value == null) {
            return;
        }
        if (value.getType() != CustomValue.CvType.OBJECT) {
            LOGGER.warn("Mod '{}' sets {} to something other than an object; ignored", metadata.getId(), CUSTOM_VALUE);
            return;
        }
        for (var entry : value.getAsObject()) {
            var background = Identifier.tryParse(entry.getKey());
            var fallback = entry.getValue().getType() == CustomValue.CvType.STRING
                    ? Identifier.tryParse(entry.getValue().getAsString()) : null;
            if (background == null || fallback == null) {
                LOGGER.warn("Mod '{}' has an invalid {} entry '{}'; ignored", metadata.getId(), CUSTOM_VALUE, entry.getKey());
                continue;
            }
            FALLBACKS.put(background, fallback);
        }
    }
}
