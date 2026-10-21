package eu.pb4.polymer.core.impl.other;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.block.PolymerSignEditing;
import eu.pb4.polymer.core.api.block.SignEditDialog;
import eu.pb4.polymer.core.impl.PolymerImpl;
import eu.pb4.polymer.core.impl.interfaces.PolymerCommonPacketListenerExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Open sign edit dialog of one player. Lives on the player's connection until submitted or invalidated.
 */
@ApiStatus.Internal
public record SignEditSession(long token, ServerLevel level, BlockPos pos, SignBlockEntity sign, boolean front, int[] maxLengths,
                              int lineWidth, SignEditDialog.@Nullable LineFitter fitter) {
    private static final int LINES = 4;
    private static final String TOKEN = "token";
    // ServerboundSignUpdatePacket's own limit
    private static final int MAX_LINE_LENGTH = 384;

    /**
     * Shows a dialog in place of the sign editor, if the sign has one
     *
     * @return true if vanilla editor shouldn't open
     */
    public static boolean open(ServerPlayer player, SignBlockEntity sign, boolean front) {
        var state = sign.getBlockState();
        var provider = PolymerSignEditing.getProvider(state.getBlock());
        if (provider == null || !(player.connection instanceof PolymerCommonPacketListenerExtension holder) || !(sign.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        // Client-side sign of the same wood draws the right board in its own editor
        var clientState = PolymerBlockUtils.getPolymerBlockState(state, player.connection.getPacketContext());
        if (clientState.getBlock() instanceof SignBlock clientSign && clientSign.type().equals(SignBlock.getWoodType(state.getBlock()))) {
            return false;
        }
        var dialog = provider.getSignEditDialog(player, sign, front);
        if (dialog == null) {
            return false;
        }

        var old = holder.polymer$getSignEditSession();
        holder.polymer$setSignEditSession(null);
        if (old != null && old.sign != sign) {
            old.release(player);
        }

        var token = ThreadLocalRandom.current().nextLong();
        var text = sign.getText(front ? SignTextSlot.FRONT : SignTextSlot.BACK).getMessages(player.isTextFilteringEnabled());
        var maxLengths = new int[LINES];
        var inputs = new ArrayList<Input>(LINES);
        for (int i = 0; i < LINES; i++) {
            var initial = i < text.size() ? text.get(i).getString() : "";
            if (initial.length() > MAX_LINE_LENGTH) {
                initial = initial.substring(0, MAX_LINE_LENGTH);
            }
            // An initial text longer than max_length fails to decode on the client
            maxLengths[i] = Math.max(dialog.maxChars(), initial.length());
            inputs.add(new Input("line" + i, new TextInput(dialog.inputWidth(),
                    Component.translatableWithFallback("text.polymer.sign_line", "Line %s", i + 1),
                    false, initial, maxLengths[i], Optional.empty())));
        }

        var additions = new CompoundTag();
        additions.putLong(TOKEN, token);
        player.openDialog(Holder.direct(new NoticeDialog(
                new CommonDialogData(dialog.title(), Optional.empty(), true, false, DialogAction.CLOSE, dialog.header(), inputs),
                new ActionButton(new CommonButtonData(CommonComponents.GUI_DONE, 150),
                        Optional.of(new CustomAll(PolymerSignEditing.CLICK_ID, Optional.of(additions))))
        )));
        holder.polymer$setSignEditSession(new SignEditSession(token, level, sign.getBlockPos(), sign, front, maxLengths,
                sign.getMaxTextLineWidth(), dialog.fitter()));
        return true;
    }

    /**
     * Applies a submitted dialog through vanilla sign update handling
     */
    public static void handleClick(ServerGamePacketListenerImpl handler, Optional<Tag> payload) {
        var holder = (PolymerCommonPacketListenerExtension) handler;
        var session = holder.polymer$getSignEditSession();
        if (session == null || !(payload.orElse(null) instanceof CompoundTag tag) || !(tag.get(TOKEN) instanceof LongTag(long token))
                || token != session.token) {
            PolymerImpl.LOGGER.debug("Ignoring stale sign edit from {}", handler.player.getPlainTextName());
            return;
        }
        holder.polymer$setSignEditSession(null);

        var lines = session.readLines(tag);
        if (lines == null || !session.isValid(handler.player)) {
            PolymerImpl.LOGGER.debug("Ignoring invalid sign edit from {}", handler.player.getPlainTextName());
            session.release(handler.player);
            return;
        }
        handler.handleSignUpdate(new ServerboundSignUpdatePacket(session.pos, lines, session.front ? SignTextSlot.FRONT : SignTextSlot.BACK));
    }

    public static void tick(ServerGamePacketListenerImpl handler) {
        var holder = (PolymerCommonPacketListenerExtension) handler;
        var session = holder.polymer$getSignEditSession();
        if (session != null && (!session.isValid(handler.player) || session.sign.playerIsTooFarAwayToEdit(handler.player.getUUID()))) {
            holder.polymer$setSignEditSession(null);
            session.release(handler.player);
            handler.send(ClientboundClearDialogPacket.INSTANCE);
        }
    }

    /**
     * Drops the session when the client's dialog gets replaced or closed by another screen
     */
    public static void onSend(ServerCommonPacketListenerImpl handler, Packet<?> packet) {
        if (handler instanceof ServerGamePacketListenerImpl game && (packet instanceof ClientboundShowDialogPacket
                || packet instanceof ClientboundClearDialogPacket || packet instanceof ClientboundOpenScreenPacket
                || packet instanceof ClientboundOpenSignEditorPacket || packet instanceof ClientboundOpenBookPacket
                || packet instanceof ClientboundRespawnPacket)) {
            drop(game);
        }
    }

    public static void drop(ServerGamePacketListenerImpl handler) {
        var holder = (PolymerCommonPacketListenerExtension) handler;
        var session = holder.polymer$getSignEditSession();
        if (session != null) {
            holder.polymer$setSignEditSession(null);
            session.release(handler.player);
        }
    }

    private boolean isValid(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && player.level() == this.level && this.level.hasChunkAt(this.pos)
                && this.level.getBlockEntity(this.pos) == this.sign && !this.sign.isRemoved() && !this.sign.isWaxed()
                && player.getUUID().equals(this.sign.getPlayerWhoMayEdit());
    }

    /**
     * Frees the sign for other players, as closing the vanilla editor would
     */
    private void release(ServerPlayer player) {
        if (player.getUUID().equals(this.sign.getPlayerWhoMayEdit())) {
            this.sign.setAllowedPlayerEditor(null);
        }
    }

    @Nullable
    private List<String> readLines(CompoundTag tag) {
        if (tag.size() != LINES + 1) {
            return null;
        }
        var lines = new ArrayList<String>(LINES);
        for (int i = 0; i < LINES; i++) {
            if (!(tag.get("line" + i) instanceof StringTag(String value)) || value.length() > MAX_LINE_LENGTH) {
                return null;
            }
            var line = StringUtil.filterText(value);
            if (line.length() > this.maxLengths[i]) {
                int end = this.maxLengths[i];
                line = line.substring(0, Character.isHighSurrogate(line.charAt(end - 1)) ? end - 1 : end);
            }
            if (this.fitter != null) {
                line = this.fitter.fit(line, this.lineWidth);
            }
            lines.add(line);
        }
        return lines;
    }
}
