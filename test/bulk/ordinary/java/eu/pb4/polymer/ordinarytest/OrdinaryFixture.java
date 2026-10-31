package eu.pb4.polymer.ordinarytest;

import com.google.gson.GsonBuilder;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** Identical installed fixture for reference and candidate; uses no bulk API or candidate internals. */
public final class OrdinaryFixture implements ModInitializer {
    private static final BlockPos POS = new BlockPos(3200, 80, 3200);
    private static int created;
    private static int attached;
    private static int detached;
    private static int updates;
    private static int keeps;
    private static int ticks;

    @Override
    public void onInitialize() {
        for (var block : List.of(Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG, Blocks.ACACIA_LOG,
                Blocks.BIRCH_LOG, Blocks.CHEST)) {
            BlockWithElementHolder.registerOverlay(block, new BlockWithElementHolder() {
                @Override public ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState state) {
                    created++;
                    return new OrdinaryHolder();
                }
                @Override public boolean keepElementHolder(ServerLevel level, BlockPos pos, BlockState old,
                        BlockState state, ElementHolder holder) {
                    keeps++;
                    return holder instanceof OrdinaryHolder;
                }
                @Override public boolean tickElementHolder(ServerLevel level, BlockPos pos, BlockState state) {
                    return state.is(Blocks.BIRCH_LOG);
                }
                @Override public Vec3 getElementHolderOffset(ServerLevel level, BlockPos pos, BlockState state) {
                    return state.is(Blocks.ACACIA_LOG) ? new Vec3(0, 0.25, 0) : Vec3.ZERO;
                }
            });
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            var root = Commands.literal("ordinarytest");
            for (var operation : List.of("add", "property", "keep", "offset", "ticking", "nbt", "remove", "probe")) {
                root.then(Commands.literal(operation).executes(context -> {
                    var level = context.getSource().getLevel();
                    switch (operation) {
                        case "add" -> level.setBlock(POS, Blocks.OAK_LOG.defaultBlockState(), 3);
                        case "property" -> level.setBlock(POS, Blocks.OAK_LOG.defaultBlockState()
                                .setValue(BlockStateProperties.AXIS, Direction.Axis.X), 3);
                        case "keep" -> level.setBlock(POS, Blocks.STRIPPED_OAK_LOG.defaultBlockState(), 3);
                        case "offset" -> level.setBlock(POS, Blocks.ACACIA_LOG.defaultBlockState(), 3);
                        case "ticking" -> level.setBlock(POS, Blocks.BIRCH_LOG.defaultBlockState(), 3);
                        case "nbt" -> {
                            level.setBlock(POS, Blocks.CHEST.defaultBlockState(), 3);
                            ((ChestBlockEntity) level.getBlockEntity(POS)).setItem(0, new ItemStack(Items.DIAMOND, 7));
                        }
                        case "remove" -> level.setBlock(POS, Blocks.AIR.defaultBlockState(), 3);
                    }
                    if (operation.equals("keep") && Boolean.getBoolean("polymer.ordinary.destroyRetained")) {
                        BlockBoundAttachment.get(level, POS).holder().destroy();
                    }
                    save(level, operation);
                    context.getSource().sendSuccess(() -> Component.literal("ordinary-ok " + operation), false);
                    return 1;
                }));
            }
            dispatcher.register(root);
        });
    }

    // Emit independently readable state and observed lifecycle/IDs, with no expected values in the fixture.
    private static void save(ServerLevel level, String operation) {
        var state = level.getBlockState(POS);
        var attachment = BlockBoundAttachment.get(level, POS);
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", operation);
        result.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        result.put("axis", state.hasProperty(BlockStateProperties.AXIS) ? state.getValue(BlockStateProperties.AXIS).toString() : "none");
        result.put("created", created); result.put("attached", attached); result.put("detached", detached);
        result.put("updates", updates); result.put("keeps", keeps); result.put("ticked", ticks > 0);
        result.put("ids", attachment == null ? List.of() : List.copyOf(attachment.holder().getEntityIds()));
        result.put("offsetY", attachment == null ? 0 : attachment.getPos().y - Vec3.atCenterOf(POS).y);
        result.put("shouldTick", attachment != null && attachment.shouldTick());
        var be = level.getBlockEntity(POS);
        result.put("diamonds", be instanceof ChestBlockEntity chest && chest.getItem(0).is(Items.DIAMOND)
                ? chest.getItem(0).getCount() : 0);
        try {
            Files.writeString(Path.of(System.getProperty("polymer.bulk.output"), "ordinary-" + operation + ".json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static final class OrdinaryHolder extends ElementHolder {
        private OrdinaryHolder() {
            addElement(new BlockDisplayElement(Blocks.EMERALD_BLOCK.defaultBlockState()));
        }
        @Override protected void onAttachmentSet(HolderAttachment current, HolderAttachment old) { attached++; }
        @Override protected void onAttachmentRemoved(HolderAttachment old) { detached++; }
        @Override protected void onTick() { ticks++; }
        @Override public void notifyUpdate(HolderAttachment.UpdateType type) {
            if (type == BlockBoundAttachment.BLOCK_STATE_UPDATE) updates++;
        }
    }
}
