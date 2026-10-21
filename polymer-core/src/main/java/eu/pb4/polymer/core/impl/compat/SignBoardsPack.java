package eu.pb4.polymer.core.impl.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.polymer.core.api.utils.PolymerUtils;
import eu.pb4.polymer.core.impl.other.SignBoards;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;

/**
 * Resource pack side of {@link SignBoards}; only loaded with polymer-resource-pack present.
 */
@ApiStatus.Internal
public final class SignBoardsPack {
    private SignBoardsPack() {
    }

    public static void register() {
        PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT.register(SignBoardsPack::build);
    }

    public static boolean hasMainPack(ServerPlayer player) {
        return PolymerUtils.hasResourcePack(player.connection, PolymerResourcePackUtils.getMainUuid());
    }

    private static void build(ResourcePackBuilder builder) {
        var glyphs = new HashMap<Identifier, String>();
        var providers = new JsonArray();
        char next = '\uE000';
        for (var texture : SignBoards.textures()) {
            if (builder.getData("assets/" + texture.getNamespace() + "/textures/" + texture.getPath()) == null) {
                continue;
            }
            var glyph = String.valueOf(next++);
            int height = SignBoards.isHangingTexture(texture) ? SignBoards.HANGING_HEIGHT : SignBoards.STANDING_HEIGHT;
            var provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", texture.toString());
            provider.addProperty("height", height);
            // Rises from the last line over the padding lines above it, no higher than the first line's top
            provider.addProperty("ascent", Math.min(height, 9 * SignBoards.LINES - 2));
            var chars = new JsonArray();
            chars.add(glyph);
            provider.add("chars", chars);
            providers.add(provider);
            glyphs.put(texture, glyph);
        }
        if (!glyphs.isEmpty()) {
            var font = new JsonObject();
            font.add("providers", providers);
            builder.addStringData("assets/" + SignBoards.FONT.getNamespace() + "/font/" + SignBoards.FONT.getPath() + ".json", font.toString());
        }
        SignBoards.setGlyphs(glyphs);
    }
}
