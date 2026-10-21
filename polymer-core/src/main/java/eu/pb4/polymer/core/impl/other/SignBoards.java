package eu.pb4.polymer.core.impl.other;

import eu.pb4.polymer.common.impl.CompatStatus;
import eu.pb4.polymer.core.impl.PolymerImplUtils;
import eu.pb4.polymer.core.impl.compat.SignBoardsPack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sign board previews: one bitmap glyph per board texture in the polymer:sign_boards font.
 */
@ApiStatus.Internal
public final class SignBoards {
    public static final Identifier FONT = PolymerImplUtils.id("sign_boards");
    // A dialog body is as tall as its text lines, so the glyph sits on the last of LINES lines and rises over the rest
    public static final int LINES = 6;
    public static final int STANDING_HEIGHT = 52;
    public static final int HANGING_HEIGHT = 48;
    private static final Style STYLE = Style.EMPTY.withFont(new FontDescription.Resource(FONT)).withColor(0xFFFFFF).withoutShadow();
    private static final Set<Block> BLOCKS = new LinkedHashSet<>();
    private static volatile Map<Identifier, String> glyphs = Map.of();
    private static boolean packHooked;

    private SignBoards() {
    }

    public static void register(Block block) {
        BLOCKS.add(block);
        if (!packHooked && CompatStatus.POLYMER_RESOURCE_PACK) {
            packHooked = true;
            SignBoardsPack.register();
        }
    }

    public static boolean isHanging(Block block) {
        return block instanceof CeilingHangingSignBlock || block instanceof WallHangingSignBlock;
    }

    /**
     * @return Board texture of the sign's wood, relative to textures/
     */
    @Nullable
    public static Identifier texture(Block block) {
        var wood = Identifier.tryParse(SignBlock.getWoodType(block).name());
        return wood == null ? null : wood.withPath(p -> (isHanging(block) ? "gui/hanging_signs/" : "gui/signs/") + p + ".png");
    }

    public static boolean isHangingTexture(Identifier texture) {
        return texture.getPath().startsWith("gui/hanging_signs/");
    }

    public static Set<Identifier> textures() {
        var out = new TreeSet<Identifier>();
        for (var block : BLOCKS) {
            var texture = texture(block);
            if (texture != null) {
                out.add(texture);
            }
        }
        return out;
    }

    public static void setGlyphs(Map<Identifier, String> glyphs) {
        SignBoards.glyphs = Map.copyOf(glyphs);
    }

    @Nullable
    public static PlainMessage getBoard(Block block, ServerPlayer player) {
        var texture = texture(block);
        var glyph = texture != null ? glyphs.get(texture) : null;
        if (glyph == null || !CompatStatus.POLYMER_RESOURCE_PACK || !SignBoardsPack.hasMainPack(player)) {
            return null;
        }
        return new PlainMessage(Component.literal("\n".repeat(LINES - 1) + glyph).setStyle(STYLE), 200);
    }
}
