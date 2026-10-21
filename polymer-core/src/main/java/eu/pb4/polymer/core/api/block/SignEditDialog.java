package eu.pb4.polymer.core.api.block;

import eu.pb4.polymer.core.impl.other.SignBoards;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dialog shown in place of the client's sign editor, see {@link PolymerSignBlock}
 *
 * @param title      Dialog title
 * @param header     Bodies shown above the four line inputs
 * @param inputWidth Width of each line input
 * @param maxChars   Character limit of each line. Lines with longer existing text can keep it
 * @param fitter     Optional transformation of each submitted line, applied before the sign is updated
 */
public record SignEditDialog(Component title, List<DialogBody> header, int inputWidth, int maxChars, @Nullable LineFitter fitter) {
    /**
     * Creates the default dialog: vanilla title, the board for players with the resource pack, sign item and name,
     * and line inputs as wide as a line on the sign
     */
    public static SignEditDialog defaults(ServerPlayer player, SignBlockEntity sign) {
        return builder(player, sign).build();
    }

    public static Builder builder(ServerPlayer player, SignBlockEntity sign) {
        return new Builder(player, sign);
    }

    @FunctionalInterface
    public interface LineFitter {
        /**
         * @param line Submitted line, already filtered and limited to the character limit
         * @param lineWidth Maximum width of a line on the sign, in pixels
         * @return Line to write on the sign
         */
        String fit(String line, int lineWidth);
    }

    public static final class Builder {
        private final ServerPlayer player;
        private final Block block;
        private final int lineWidth;
        private Component title;
        private ItemStack item;
        private Component name;
        private boolean board = true;
        private final List<DialogBody> body = new ArrayList<>();
        private int maxChars;
        private int inputWidth;
        @Nullable
        private LineFitter fitter;

        private Builder(ServerPlayer player, SignBlockEntity sign) {
            this.player = player;
            this.block = sign.getBlockState().getBlock();
            this.lineWidth = sign.getMaxTextLineWidth();
            this.title = Component.translatable(SignBoards.isHanging(this.block) ? "hanging_sign.edit" : "sign.edit");
            this.item = new ItemStack(this.block);
            this.name = withFallback(this.item.isEmpty() ? this.block.getName() : this.item.getHoverName());
            // The narrowest glyph is 2 pixels wide, so this never blocks text the client's editor would accept
            this.maxChars = this.lineWidth / 2;
            this.inputWidth = this.lineWidth + 8;
        }

        public Builder title(Component title) {
            this.title = title;
            return this;
        }

        /**
         * Sets the name shown next to the item
         */
        public Builder name(Component name) {
            this.name = name;
            return this;
        }

        /**
         * Sets item shown in the header. Empty stack hides it
         */
        public Builder item(ItemStack item) {
            this.item = item;
            return this;
        }

        /**
         * Shows the sign's board above the inputs, for players with the main resource pack.
         * Requires the block to be registered with {@link PolymerSignEditing} and its gui texture to be in the pack
         */
        public Builder board(boolean board) {
            this.board = board;
            return this;
        }

        /**
         * Adds a body shown under the item
         */
        public Builder body(DialogBody body) {
            this.body.add(body);
            return this;
        }

        public Builder maxChars(int maxChars) {
            this.maxChars = maxChars;
            return this;
        }

        public Builder inputWidth(int inputWidth) {
            this.inputWidth = inputWidth;
            return this;
        }

        public Builder fitter(@Nullable LineFitter fitter) {
            this.fitter = fitter;
            return this;
        }

        // Clients without the mod's language file, as without the resource pack, get the server's English
        private static Component withFallback(Component name) {
            if (name.getContents() instanceof TranslatableContents contents && contents.getFallback() == null
                    && name.getSiblings().isEmpty() && Language.getInstance().has(contents.getKey())) {
                return Component.translatableWithFallback(contents.getKey(), Language.getInstance().getOrDefault(contents.getKey()),
                        contents.getArgs()).setStyle(name.getStyle());
            }
            return name;
        }

        public SignEditDialog build() {
            var header = new ArrayList<DialogBody>();
            if (this.board) {
                var board = SignBoards.getBoard(this.block, this.player);
                if (board != null) {
                    header.add(board);
                }
            }
            if (!this.item.isEmpty()) {
                header.add(new ItemBody(ItemStackTemplate.fromNonEmptyStack(this.item), Optional.of(new PlainMessage(this.name, 200)), false, false, 16, 16));
            } else {
                header.add(new PlainMessage(this.name, 200));
            }
            header.addAll(this.body);
            return new SignEditDialog(this.title, List.copyOf(header), this.inputWidth, this.maxChars, this.fitter);
        }
    }
}
