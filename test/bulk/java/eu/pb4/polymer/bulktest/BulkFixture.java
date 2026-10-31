package eu.pb4.polymer.bulktest;

import com.google.gson.GsonBuilder;
import eu.pb4.polymer.common.api.PolymerBulkEdits;
import eu.pb4.polymer.common.impl.BulkChunkAccess;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.impl.interfaces.PolymerChunkSectionStorage;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Installed-jar fixture: native section writes, real holders, independent palette oracle. */
public final class BulkFixture implements ModInitializer {
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static int created;
    private static int attached;
    private static int detached;
    private static int updates;
    private static int nbtUpdates;
    private static int keepCalls;
    private static boolean throwCreate;
    private static boolean throwKeep;
    private static boolean throwUpdate;
    private static boolean recipientFault;
    private static int ticks;
    private static final List<Map<String, String>> NEGATIVES = new ArrayList<>();
    private static PolymerBulkEdits.Token unloadedToken;
    private static ElementHolder unloadedHolder;
    private static int pendingTicks;
    private static net.minecraft.server.network.ServerGamePacketListenerImpl disconnected;
    private static PolymerBulkEdits.Token pending;
    private static LevelChunk observedChunk;
    private static BlockPos origin = new BlockPos(3200, 80, 3200);

    @Override
    public void onInitialize() {
        PolymerBlockUtils.registerOverlay(Blocks.REDSTONE_LAMP, new PolymerBlock() {
            @Override public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
                return !recipientFault && eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.hasMainPack(context)
                        ? Blocks.WOOL.blue().defaultBlockState() : Blocks.WOOL.red().defaultBlockState();
            }
            @Override public boolean forceLightUpdates(BlockState state) { return state.getValue(BlockStateProperties.LIT); }
            @Override public boolean forceLightInsideBlock(BlockState state) { return state.getValue(BlockStateProperties.LIT); }
        });
        for (var block : List.of(Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG, Blocks.BIRCH_LOG, Blocks.ACACIA_LOG, Blocks.JUNGLE_LOG, Blocks.CHEST)) {
            BlockWithElementHolder.registerOverlay(block, new BlockWithElementHolder() {
                @Override public ElementHolder createElementHolder(ServerLevel world, BlockPos pos, BlockState state) {
                    if (throwCreate) throw new IllegalStateException("deliberate-create-failure");
                    created++;
                    return new FixtureHolder();
                }
                @Override public boolean keepElementHolder(ServerLevel world, BlockPos pos, BlockState oldState,
                                                           BlockState state, ElementHolder holder) {
                    keepCalls++;
                    if (throwKeep) throw new IllegalStateException("deliberate-keep-failure");
                    return holder instanceof FixtureHolder;
                }
                @Override public boolean tickElementHolder(ServerLevel world, BlockPos pos, BlockState state) {
                    return state.is(Blocks.BIRCH_LOG) || (state.is(Blocks.JUNGLE_LOG)
                            && state.getValue(BlockStateProperties.AXIS) == Direction.Axis.X);
                }
                @Override public Vec3 getElementHolderOffset(ServerLevel world, BlockPos pos, BlockState state) {
                    return state.is(Blocks.ACACIA_LOG) || (state.is(Blocks.JUNGLE_LOG)
                            && state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Z)
                            ? new Vec3(0, 0.25, 0) : Vec3.ZERO;
                }
            });
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            var root = Commands.literal("bulktest");
            for (String operation : List.of("run", "prepare", "publish", "restart", "watched", "unwatched", "unloaded", "lit",
                    "focused", "recipientfault", "recipientrestore", "disconnectprepare", "disconnectpublished",
                    "unloadprepare", "pendingunloaded", "pendingreload", "tickprepare", "tickheld", "tickpublished",
                    "manualattach", "touchbe")) {
                root.then(Commands.literal(operation).executes(context -> {
                    try {
                        var level = context.getSource().getLevel();
                        switch (operation) {
                            case "run" -> run(level);
                            case "prepare" -> scene(level);
                            case "focused" -> focused(level);
                            case "recipientfault", "recipientrestore" -> {
                                recipientFault = operation.equals("recipientfault");
                                try (var token = replace(level.getChunkAt(origin), Map.of())) { token.publish(); }
                            }
                            case "disconnectprepare" -> {
                                observedChunk = level.getChunkAt(origin);
                                disconnected = level.getServer().getPlayerList().getPlayerByName("polymerone").connection;
                                pending = replace(observedChunk, Map.of(origin.offset(4, 0, 0), Blocks.OAK_LOG.defaultBlockState()));
                                eq("disconnect-new-unwatched", BlockBoundAttachment.get(observedChunk, origin.offset(4, 0, 0))
                                        .holder().getWatchingPlayers().size(), 0);
                            }
                            case "disconnectpublished" -> {
                                var watchers = BlockBoundAttachment.get(observedChunk, origin.offset(4, 0, 0)).holder().getWatchingPlayers();
                                eq("disconnect-current-recipient-only", watchers.size(), 1);
                                eq("disconnect-live-recipient", watchers.iterator().next().player.getName().getString(), "polymertwo");
                                watchers.add(disconnected);
                                negative("leaked-disconnected-watcher", () -> eq("disconnect-current-recipient-only", watchers.size(), 1));
                                watchers.remove(disconnected);
                                released(observedChunk, false);
                            }
                            case "manualattach" -> {
                                observedChunk = level.getChunkAt(origin);
                                new eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment(
                                        new ElementHolder(), observedChunk, Vec3.atCenterOf(origin.offset(7, 0, 0)), false);
                                eq("manual-attachment-present", ((eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder) observedChunk).polymerVE$getHolders().stream()
                                        .anyMatch(attachment -> !(attachment instanceof BlockBoundAttachment)), true);
                            }
                            case "touchbe" -> Objects.requireNonNull(level.getBlockEntity(origin.offset(3, 0, 0)),
                                    "fixture chest present").setChanged();
                            case "tickprepare" -> {
                                var tickPos = origin.offset(6, 0, 0);
                                level.setBlock(tickPos, Blocks.JUNGLE_LOG.defaultBlockState(), 3);
                                pending = replace(level.getChunkAt(origin), Map.of(tickPos, Blocks.JUNGLE_LOG.defaultBlockState()
                                        .setValue(BlockStateProperties.AXIS, Direction.Axis.X)));
                                pendingTicks = ticks;
                            }
                            case "tickheld" -> {
                                eq("pending-tick-paused", ticks, pendingTicks);
                                ((FixtureHolder) BlockBoundAttachment.get(level, origin.offset(6, 0, 0)).holder()).onTick();
                                negative("bypass-pending-tick-gate", () -> eq("pending-tick-paused", ticks, pendingTicks));
                                pendingTicks = ticks;
                            }
                            case "tickpublished" -> {
                                if (ticks == pendingTicks) return 0;
                                eq("published-ticking-resumes", ticks > pendingTicks, true);
                            }
                            case "unloadprepare" -> {
                                observedChunk = level.getChunkAt(origin);
                                unloadedToken = replace(observedChunk, Map.of(origin.offset(5, 0, 0), Blocks.OAK_LOG.defaultBlockState()));
                                unloadedHolder = BlockBoundAttachment.get(observedChunk, origin.offset(5, 0, 0)).holder();
                                eq("pending-unload-no-watchers", unloadedHolder.getWatchingPlayers().isEmpty(), true);
                            }
                            case "pendingunloaded" -> {
                                if (level.getChunkSource().getChunkNow(200, 200) != null) return 0;
                                released(observedChunk, true);
                                eq("pending-unload-destroyed", unloadedHolder.getAttachment() == null, true);
                                eq("pending-unload-empty-watchers", unloadedHolder.getWatchingPlayers().isEmpty(), true);
                                emptyHolders(observedChunk);
                                var leaked = new eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment(
                                        new ElementHolder(), observedChunk, Vec3.atCenterOf(origin), false);
                                negative("leaked-unloaded-attachment", () -> emptyHolders(observedChunk));
                                leaked.destroy();
                                emptyHolders(observedChunk);
                                reject("pending-unload-publish-refused", unloadedToken::publish);
                                unloadedToken.close();
                            }
                            case "pendingreload" -> {
                                var fresh = level.getChunkAt(origin);
                                eq("reload-new-chunk-identity", fresh != observedChunk, true);
                                released(fresh, false);
                                try (var token = PolymerBulkEdits.prepare(fresh, Set.of(5), Map.of())) { }
                                eq("reload-one-holder", BlockBoundAttachment.get(fresh, origin.offset(5, 0, 0)) != null, true);
                                index(fresh);
                                observedChunk = fresh;
                            }
                            case "lit" -> {
                                if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, origin.offset(1, 1, 0)) != 14) {
                                    context.getSource().sendSuccess(() -> Component.literal("bulk-wait lighting"), false);
                                    return 0;
                                }
                            }
                            case "publish" -> { pending.publish(); pending.close(); pending = null; }
                            case "watched", "unwatched" -> eq(operation,
                                    BlockBoundAttachment.get(observedChunk, origin).holder().getWatchingPlayers().size(),
                                    operation.equals("watched") ? 2 : 0);
                            case "unloaded" -> {
                                if (level.getChunkSource().getChunkNow(200, 200) != null) {
                                    context.getSource().sendSuccess(() -> Component.literal("bulk-wait loaded"), false);
                                    return 0;
                                }
                                eq("unload-destroys-holders", ((eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder) observedChunk).polymerVE$getHolders().isEmpty(), true);
                                boolean refused = false;
                                try { PolymerBulkEdits.prepare(observedChunk, Set.of(5), Map.of()); }
                                catch (IllegalStateException expected) { refused = true; }
                                eq("unloaded-instance-refused", refused, true);
                            }
                            case "restart" -> {
                                eq("saved-log", level.getBlockState(origin).is(Blocks.OAK_LOG), true);
                                eq("saved-nbt", ((ChestBlockEntity) level.getBlockEntity(origin.offset(3, 0, 0))).getItem(0).getCount(), 7);
                                var savedChest = (ChestBlockEntity) level.getBlockEntity(origin.offset(3, 0, 0));
                                var savedItem = savedChest.getItem(0).copy();
                                savedChest.setItem(0, ItemStack.EMPTY);
                                negative("corrupt-reloaded-nbt", () -> eq("saved-nbt", savedChest.getItem(0).getCount(), 7));
                                savedChest.setItem(0, savedItem);
                                eq("loaded-holder", BlockBoundAttachment.get(level, origin) != null, true);
                                index(level.getChunkAt(origin));
                            }
                        }
                        save(operation, null);
                        context.getSource().sendSuccess(() -> Component.literal("bulk-ok " + operation), false);
                        return 1;
                    } catch (Throwable error) {
                        save(operation, error);
                        throw new IllegalStateException("bulk-fixture " + operation, error);
                    }
                }));
            }
            dispatcher.register(root);
        });
    }

    private static void run(ServerLevel level) {
        RESULTS.clear();
        var chunk = level.getChunkAt(origin);
        var pos = origin;
        var lamp = origin.offset(1, 0, 0);
        var unchanged = origin.offset(2, 0, 0);
        level.setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), 3);
        level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true), 3);
        level.setBlock(unchanged, Blocks.OAK_LOG.defaultBlockState(), 3);
        var holder = BlockBoundAttachment.get(chunk, pos).holder();
        var stable = BlockBoundAttachment.get(chunk, unchanged).holder();
        int count = created;
        try (var token = PolymerBulkEdits.prepare(chunk, Set.of(5), Map.of())) {
            eq("prepare-no-creation", created, count);
        }
        eq("cancel-no-quarantine", ((BulkChunkAccess) chunk).polymer$bulkQuarantined(), false);
        var token = replace(chunk, Map.of(pos, Blocks.STRIPPED_OAK_LOG.defaultBlockState()));
        eq("keep-before-write", keepCalls > 0, true);
        eq("keep-identity", BlockBoundAttachment.get(chunk, pos).holder() == holder, true);
        eq("unchanged-identity", BlockBoundAttachment.get(chunk, unchanged).holder() == stable, true);
        eq("no-recreate-keep", created, count);
        var receipt = token.reconcile();
        eq("duplicate-reconcile", token.reconcile() == receipt, true);
        token.publish(); token.publish(); token.close();
        eq("duplicate-publish", created, count);
        token = replace(chunk, Map.of(pos, Blocks.STRIPPED_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X)));
        eq("property-identity", BlockBoundAttachment.get(chunk, pos).holder() == holder, true);
        token.publish(); token.close();
        token = replace(chunk, Map.of(pos, Blocks.BIRCH_LOG.defaultBlockState()));
        eq("ticking-replaces", BlockBoundAttachment.get(chunk, pos).holder() != holder, true);
        eq("ticking-enabled", BlockBoundAttachment.get(chunk, pos).shouldTick(), true);
        token.publish(); token.close();
        holder = BlockBoundAttachment.get(chunk, pos).holder();
        token = replace(chunk, Map.of(pos, Blocks.ACACIA_LOG.defaultBlockState()));
        eq("offset-replaces", BlockBoundAttachment.get(chunk, pos).holder() != holder, true);
        eq("offset-exact", BlockBoundAttachment.get(chunk, pos).getPos(), Vec3.atCenterOf(pos).add(0, 0.25, 0));
        token.publish(); token.close();
        token = replace(chunk, Map.of(pos, Blocks.AIR.defaultBlockState(), lamp, Blocks.AIR.defaultBlockState()));
        eq("remove-holder", BlockBoundAttachment.get(chunk, pos) == null, true);
        var storage = (PolymerChunkSectionStorage) chunk.getSection(chunk.getSectionIndex(pos.getY()));
        eq("last-light-removed", storage.polymer$hasLights(), false);
        eq("last-inside-removed", storage.polymer$lightInsideIterator(net.minecraft.core.SectionPos.of(pos)).hasNext(), false);
        eq("last-light-invalidation", storage.polymer$requireLights(), true);
        index(chunk);
        token.publish(); token.close();
        token = replace(chunk, Map.of(pos, Blocks.OAK_LOG.defaultBlockState(), lamp, Blocks.REDSTONE_LAMP.defaultBlockState()));
        eq("add-holder", BlockBoundAttachment.get(chunk, pos) != null, true);
        token.publish(); token.close();
        index(chunk);
        // Deliberately corrupt the live index, verify the independent scanner catches it, then repair.
        storage = (PolymerChunkSectionStorage) chunk.getSection(chunk.getSectionIndex(pos.getY()));
        storage.polymer$getBackendSet().clear();
        boolean caught = false;
        try { index(chunk); } catch (AssertionError expected) { caught = true; }
        eq("negative-missing-index", caught, true);
        token = replace(chunk, Map.of()); token.publish(); token.close(); index(chunk);
        var chestPos = origin.offset(3, 0, 0);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        var chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
        int nbtBefore = nbtUpdates;
        token = PolymerBulkEdits.prepare(chunk, Set.of(5), Map.of());
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var nbtChanges = token.reconcile();
        eq("nbt-only-delta", nbtChanges.size(), 1);
        eq("nbt-only-kind", nbtChanges.getFirst().before() == nbtChanges.getFirst().after(), true);
        eq("nbt-notify-deferred", nbtUpdates, nbtBefore);
        token.publish(); token.close();
        eq("nbt-notify-once", nbtUpdates, nbtBefore + 1);
        // Wrong-thread calls must refuse before observing/mutating chunk state.
        boolean wrongThread = CompletableFuture.supplyAsync(() -> {
            try { PolymerBulkEdits.prepare(chunk, Set.of(5), Map.of()); return false; }
            catch (IllegalStateException expected) { return true; }
        }).join();
        eq("wrong-thread-refused", wrongThread, true);
        count = created;
        level.setBlock(pos, Blocks.STRIPPED_OAK_LOG.defaultBlockState(), 3);
        eq("ordinary-keep", created, count);
        index(chunk);
        // A failed factory after a committed direct write is never replayed or reported as success.
        var failurePos = origin.offset(160, 0, 0);
        var failureChunk = level.getChunkAt(failurePos);
        token = PolymerBulkEdits.prepare(failureChunk, Set.of(5), Map.of(failurePos, Blocks.OAK_LOG.defaultBlockState()));
        swap(failureChunk, Map.of(failurePos, Blocks.OAK_LOG.defaultBlockState()));
        throwCreate = true;
        caught = false;
        try { token.reconcile(); } catch (IllegalStateException expected) { caught = true; }
        finally { throwCreate = false; token.close(); }
        eq("failure-propagated", caught, true);
        eq("failure-actual-delta", token.changes().size(), 1);
        eq("failure-no-replay", failureChunk.getBlockState(failurePos).is(Blocks.OAK_LOG), true);
        eq("failure-release", ((BulkChunkAccess) failureChunk).polymer$bulkToken() == null, true);
        eq("failure-quarantine", ((BulkChunkAccess) failureChunk).polymer$bulkQuarantined(), true);
        edges(level);
    }

    private static void edges(ServerLevel level) {
        var pos = origin.offset(192, 0, 0);
        var chunk = level.getChunkAt(pos);
        var proposal = Map.of(pos, Blocks.OAK_LOG.defaultBlockState(), pos.offset(1, 0, 0), Blocks.OAK_LOG.defaultBlockState());
        var token = PolymerBulkEdits.prepare(chunk, Set.of(5), proposal);
        boolean caught = false;
        try { PolymerBulkEdits.prepare(chunk, Set.of(5), proposal); }
        catch (IllegalStateException expected) { caught = true; }
        eq("overlapping-token-refused", caught, true);
        swap(chunk, Map.of(pos, Blocks.OAK_LOG.defaultBlockState()));
        eq("partial-actual-only", token.reconcile().size(), 1);
        eq("unwritten-proposal-no-holder", BlockBoundAttachment.get(chunk, pos.offset(1, 0, 0)) == null, true);
        token.publish(); token.close();

        token = PolymerBulkEdits.prepare(chunk, Set.of(5), Map.of(pos, Blocks.STRIPPED_OAK_LOG.defaultBlockState()));
        swap(chunk, Map.of(pos, Blocks.STRIPPED_OAK_LOG.defaultBlockState()));
        token.reconcile();
        var section = chunk.getSection(chunk.getSectionIndex(80));
        section.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState());
        section.setBlockState(0, 0, 0, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        caught = false;
        try { token.publish(); } catch (IllegalStateException expected) { caught = true; }
        finally { token.close(); }
        eq("section-aba-refused", caught, true);
        eq("section-aba-release", ((BulkChunkAccess) chunk).polymer$bulkToken() == null, true);

        pos = origin.offset(224, 0, 0); chunk = level.getChunkAt(pos);
        token = PolymerBulkEdits.prepare(chunk, Set.of(5), Map.of(pos, Blocks.OAK_LOG.defaultBlockState()));
        swap(chunk, Map.of(pos, Blocks.OAK_LOG.defaultBlockState())); token.reconcile();
        chunk.getSections()[chunk.getSectionIndex(80)] = chunk.getSection(chunk.getSectionIndex(80)).copy();
        caught = false;
        try { token.publish(); } catch (IllegalStateException expected) { caught = true; }
        finally { token.close(); }
        eq("section-identity-refused", caught, true);

        pos = origin.offset(256, 0, 0); chunk = level.getChunkAt(pos);
        token = PolymerBulkEdits.prepare(chunk, Set.of(5), Map.of());
        level.setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), 3);
        caught = false;
        try { token.reconcile(); } catch (IllegalStateException expected) { caught = true; }
        finally { token.close(); }
        eq("ordinary-invalidates-token", caught, true);
        eq("ordinary-survives-overlap", BlockBoundAttachment.get(chunk, pos) != null, true);

        pos = origin.offset(288, 0, 0); chunk = level.getChunkAt(pos);
        token = PolymerBulkEdits.prepare(chunk, Set.of(5), Map.of(pos, Blocks.OAK_LOG.defaultBlockState()));
        swap(chunk, Map.of(pos, Blocks.BIRCH_LOG.defaultBlockState()));
        caught = false;
        try { token.reconcile(); } catch (IllegalStateException expected) { caught = true; }
        finally { token.close(); }
        eq("unplanned-write-refused", caught, true);
        eq("unplanned-write-actual-delta", token.changes().getFirst().after().is(Blocks.BIRCH_LOG), true);

        pos = origin.offset(320, 0, 0); chunk = level.getChunkAt(pos);
        var manual = new eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment(new ElementHolder(), chunk, Vec3.atCenterOf(pos), false);
        caught = false;
        try { PolymerBulkEdits.prepare(chunk, Set.of(5), Map.of()); }
        catch (UnsupportedOperationException expected) { caught = true; }
        finally { manual.destroy(); }
        eq("unknown-manual-refused", caught, true);
        eq("prepare-failure-release", ((BulkChunkAccess) chunk).polymer$bulkToken() == null, true);
    }

    // Required continuation gates and destructive controls use separate chunks, never retry failed mutations.
    private static void focused(ServerLevel level) {
        var pos = origin.offset(352, 0, 0);
        var chunk = level.getChunkAt(pos);
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        var chest = (ChestBlockEntity) level.getBlockEntity(pos);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var oldHolder = BlockBoundAttachment.get(chunk, pos).holder();
        int count = created;
        throwKeep = true;
        try {
            reject("pre-capture-keep-failure", () -> PolymerBulkEdits.prepare(chunk, Set.of(5),
                    Map.of(pos, Blocks.STRIPPED_OAK_LOG.defaultBlockState())));
        } finally { throwKeep = false; }
        released(chunk, false);
        eq("keep-failure-no-mutation", chunk.getBlockState(pos).is(Blocks.CHEST), true);
        eq("keep-failure-be-identity", level.getBlockEntity(pos) == chest, true);
        eq("keep-failure-nbt", chest.getItem(0).getCount(), 7);
        eq("keep-failure-holder-identity", BlockBoundAttachment.get(chunk, pos).holder() == oldHolder, true);
        eq("keep-failure-no-create", created, count);
        var cancelled = PolymerBulkEdits.prepare(chunk, Set.of(5), Map.of());
        negative("withheld-close", () -> released(chunk, false));
        cancelled.close(); released(chunk, false);

        var propertyPos = origin.offset(384, 0, 0);
        var propertyChunk = level.getChunkAt(propertyPos);
        var initial = Blocks.JUNGLE_LOG.defaultBlockState();
        level.setBlock(propertyPos, initial, 3);
        var first = BlockBoundAttachment.get(propertyChunk, propertyPos).holder();
        var tickState = initial.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
        int detachBefore = detached;
        int attachBefore = attached;
        int createBefore = created;
        try (var token = replace(propertyChunk, Map.of(propertyPos, tickState))) {
            eq("property-tick-detach-once", detached, detachBefore + 1);
            eq("property-tick-attach-once", attached, attachBefore + 1);
            eq("property-tick-create-once", created, createBefore + 1);
            compatible(propertyChunk, propertyPos, true, 0);
            eq("property-tick-new-id", BlockBoundAttachment.get(propertyChunk, propertyPos).holder() != first, true);
            token.publish();
        }
        final int expectedCreates = createBefore + 1;
        var duplicate = BlockWithElementHolder.get(tickState).createElementHolder(level, propertyPos, tickState);
        negative("duplicate-factory-callback", () -> eq("property-tick-create-once", created, expectedCreates));
        duplicate.destroy();
        var second = BlockBoundAttachment.get(propertyChunk, propertyPos).holder();
        var offsetState = initial.setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
        detachBefore = detached; attachBefore = attached; createBefore = created;
        try (var token = replace(propertyChunk, Map.of(propertyPos, offsetState))) {
            eq("property-offset-detach-once", detached, detachBefore + 1);
            eq("property-offset-attach-once", attached, attachBefore + 1);
            eq("property-offset-create-once", created, createBefore + 1);
            compatible(propertyChunk, propertyPos, false, 0.25);
            eq("property-offset-new-id", BlockBoundAttachment.get(propertyChunk, propertyPos).holder() != second, true);
            token.publish();
        }
        // A real incompatible attachment must fail the same compatibility oracle.
        BlockBoundAttachment.get(propertyChunk, propertyPos).holder().destroy();
        var wrong = new FixtureHolder();
        var corrupt = new BlockBoundAttachment(wrong, propertyChunk, offsetState, propertyPos,
                Vec3.atCenterOf(propertyPos), true);
        negative("corrupt-property-ticking", () -> compatible(propertyChunk, propertyPos, false, 0.25));
        corrupt.destroy();
        corrupt = new BlockBoundAttachment(new FixtureHolder(), propertyChunk, offsetState, propertyPos,
                Vec3.atCenterOf(propertyPos), false);
        negative("corrupt-property-offset", () -> compatible(propertyChunk, propertyPos, false, 0.25));
        corrupt.destroy();
        BlockBoundAttachment.of(new FixtureHolder(), level, propertyChunk, propertyPos, offsetState);
        compatible(propertyChunk, propertyPos, false, 0.25);

        var notifyPos = origin.offset(416, 0, 0);
        var notifyChunk = level.getChunkAt(notifyPos);
        level.setBlock(notifyPos, Blocks.OAK_LOG.defaultBlockState(), 3);
        var retained = BlockBoundAttachment.get(notifyChunk, notifyPos).holder();
        int beforeUpdates = updates;
        try (var token = replace(notifyChunk, Map.of(notifyPos, Blocks.STRIPPED_OAK_LOG.defaultBlockState()))) {
            final int expected = beforeUpdates + 1;
            negative("withheld-update-publication", () -> eq("retained-notify-once", updates, expected));
            token.publish();
            eq("retained-notify-once", updates, expected);
            retained.notifyUpdate(BlockBoundAttachment.BLOCK_STATE_UPDATE);
            negative("duplicate-update-callback", () -> eq("retained-notify-once", updates, expected));
        }
        identity(notifyChunk, notifyPos, retained);
        retained.destroy();
        negative("destroy-retained-holder", () -> identity(notifyChunk, notifyPos, retained));

        var nbtPos = origin.offset(448, 0, 0);
        var nbtChunk = level.getChunkAt(nbtPos);
        level.setBlock(nbtPos, Blocks.CHEST.defaultBlockState(), 3);
        int beforeNbt = nbtUpdates;
        try (var token = PolymerBulkEdits.prepare(nbtChunk, Set.of(5), Map.of())) {
            ((ChestBlockEntity) level.getBlockEntity(nbtPos)).setItem(0, new ItemStack(Items.DIAMOND, 9));
            token.reconcile();
            negative("withheld-nbt-publication", () -> eq("nbt-notify-once", nbtUpdates, beforeNbt + 1));
            token.publish();
            eq("nbt-notify-once", nbtUpdates, beforeNbt + 1);
        }

        var updatePos = origin.offset(480, 0, 0);
        var updateChunk = level.getChunkAt(updatePos);
        level.setBlock(updatePos, Blocks.OAK_LOG.defaultBlockState(), 3);
        var updateHolder = BlockBoundAttachment.get(updateChunk, updatePos).holder();
        var failed = replace(updateChunk, Map.of(updatePos, Blocks.STRIPPED_OAK_LOG.defaultBlockState()));
        throwUpdate = true;
        try { reject("publication-update-failure", failed::publish); }
        finally { throwUpdate = false; failed.close(); }
        released(updateChunk, true);
        eq("publish-failure-actual-delta", failed.changes().size(), 1);
        eq("publish-failure-no-retry", updateChunk.getBlockState(updatePos).is(Blocks.STRIPPED_OAK_LOG), true);
        eq("publish-failure-destroyed", updateHolder.getAttachment() == null, true);
        eq("publish-failure-unwatched", updateHolder.getWatchingPlayers().isEmpty(), true);
        reject("publish-failure-cannot-relabel", failed::publish);

        var indexPos = origin.offset(512, 0, 0);
        var indexChunk = level.getChunkAt(indexPos);
        level.setBlock(indexPos, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true), 3);
        try (var token = replace(indexChunk, Map.of())) { token.publish(); }
        var storage = (PolymerChunkSectionStorage) indexChunk.getSection(indexChunk.getSectionIndex(80));
        storage.polymer$setSynced(0, 0, 0, false, false);
        negative("corrupt-inside-index", () -> index(indexChunk));
        try (var token = replace(indexChunk, Map.of())) { token.publish(); }
        storage = (PolymerChunkSectionStorage) indexChunk.getSection(indexChunk.getSectionIndex(80));
        storage.polymer$setSynced(0, 0, 0, false, true);
        negative("corrupt-forced-light-index", () -> index(indexChunk));
        try (var token = replace(indexChunk, Map.of())) { token.publish(); }
        try (var token = replace(indexChunk, Map.of(indexPos, Blocks.AIR.defaultBlockState()))) {
            eq("last-light-invalidation", ((PolymerChunkSectionStorage) indexChunk.getSection(indexChunk.getSectionIndex(80)))
                    .polymer$requireLights(), true);
            token.publish();
        }
        storage = (PolymerChunkSectionStorage) indexChunk.getSection(indexChunk.getSectionIndex(80));
        storage.polymer$setRequireLights(false);
        var brokenLight = storage;
        negative("omit-last-light-invalidation", () -> eq("last-light-invalidation", brokenLight.polymer$requireLights(), true));
        storage.polymer$setRequireLights(true);
        var ghost = BlockBoundAttachment.of(new FixtureHolder(), level, indexChunk, indexPos, Blocks.OAK_LOG.defaultBlockState());
        negative("stale-holder-after-remove", () -> eq("remove-holder", BlockBoundAttachment.get(indexChunk, indexPos) == null, true));
        ghost.destroy();
        eq("remove-holder", BlockBoundAttachment.get(indexChunk, indexPos) == null, true);
        var omitted = origin.offset(544, 0, 0);
        var omittedChunk = level.getChunkAt(omitted);
        try (var token = PolymerBulkEdits.prepare(omittedChunk, Set.of(5), Map.of(omitted, initial))) {
            token.reconcile(); token.publish();
        }
        negative("withheld-add-mutation", () -> eq("add-holder", BlockBoundAttachment.get(omittedChunk, omitted) != null, true));

        // A block-entity setChanged (chunk-wide revision bump) between reconcile and publication
        // must not fail publication: enforcement is scoped to the reserved sections.
        var pokePos = origin.offset(576, 0, 0);
        var pokeChunk = level.getChunkAt(pokePos);
        level.setBlock(pokePos, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(pokePos.offset(0, 0, 1), Blocks.CHEST.defaultBlockState(), 3);
        var surviving = (ChestBlockEntity) level.getBlockEntity(pokePos.offset(0, 0, 1));
        var pokeToken = replace(pokeChunk, Map.of(pokePos, Blocks.OAK_LOG.defaultBlockState()));
        surviving.setChanged();
        pokeToken.publish();
        pokeToken.close();
        released(pokeChunk, false);
        eq("be-setchanged-no-publish-block", ((BulkChunkAccess) pokeChunk).polymer$bulkQuarantined(), false);
        eq("be-setchanged-applied", pokeChunk.getBlockState(pokePos).is(Blocks.OAK_LOG), true);
    }

    private static void identity(LevelChunk chunk, BlockPos pos, ElementHolder holder) {
        var attachment = BlockBoundAttachment.get(chunk, pos);
        eq("retained-live-identity", attachment != null && attachment.holder() == holder, true);
    }

    private static void emptyHolders(LevelChunk chunk) {
        eq("unload-destroys-holders", ((eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder) chunk)
                .polymerVE$getHolders().isEmpty(), true);
    }

    private static void released(LevelChunk chunk, boolean quarantined) {
        eq("token-cleanup", ((BulkChunkAccess) chunk).polymer$bulkToken() == null, true);
        eq("quarantine-ownership", ((BulkChunkAccess) chunk).polymer$bulkQuarantined(), quarantined);
    }

    private static void compatible(LevelChunk chunk, BlockPos pos, boolean ticking, double offset) {
        var attachment = BlockBoundAttachment.get(chunk, pos);
        eq("property-ticking", attachment.shouldTick(), ticking);
        eq("property-offset", attachment.getPos(), Vec3.atCenterOf(pos).add(0, offset, 0));
    }

    // Deliberate fixture faults must fail the same oracle used in the corresponding success case.
    private static void negative(String label, Runnable oracle) {
        try { oracle.run(); }
        catch (AssertionError expected) {
            NEGATIVES.add(Map.of("control", label, "expectedFailure", expected.toString()));
            return;
        }
        throw new AssertionError("Negative control accepted: " + label);
    }

    private static void reject(String label, Runnable operation) {
        try { operation.run(); }
        catch (IllegalStateException expected) {
            NEGATIVES.add(Map.of("control", label, "expectedFailure", expected.toString()));
            return;
        }
        throw new AssertionError("Unsafe operation accepted: " + label);
    }

    private static void scene(ServerLevel level) {
        var chunk = level.getChunkAt(origin);
        observedChunk = chunk;
        var edits = new HashMap<BlockPos, BlockState>();
        edits.put(origin, Blocks.OAK_LOG.defaultBlockState());
        edits.put(origin.offset(1, 0, 0), Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        var wireStates = Blocks.REDSTONE_WIRE.getStateDefinition().getPossibleStates();
        for (int i = 0; i < 512; i++) {
            edits.put(origin.offset(i & 15, 4 + (i >> 8), (i >> 4) & 15), wireStates.get(i));
        }
        pending = replace(chunk, edits);
        level.getChunkSource().getLightEngine().checkBlock(origin.offset(1, 0, 0));
        eq("new-holder-no-watchers", BlockBoundAttachment.get(chunk, origin).holder().getWatchingPlayers().isEmpty(), true);
    }

    private static PolymerBulkEdits.Token replace(LevelChunk chunk, Map<BlockPos, BlockState> writes) {
        var token = PolymerBulkEdits.prepare(chunk, Set.of(5), writes);
        swap(chunk, writes);
        token.reconcile();
        index(chunk);
        return token;
    }

    private static void swap(LevelChunk chunk, Map<BlockPos, BlockState> writes) {
        int section = chunk.getSectionIndex(80);
        var replacement = chunk.getSection(section).copy();
        writes.forEach((pos, state) -> replacement.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state));
        chunk.getSections()[section] = replacement;
        chunk.markUnsaved();
    }

    private static void index(LevelChunk chunk) {
        var section = chunk.getSection(chunk.getSectionIndex(80));
        var expected = new HashSet<Short>();
        var inside = new HashSet<BlockPos>();
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            var state = section.getBlockState(x, y, z);
            if (state.is(Blocks.REDSTONE_LAMP)) {
                expected.add((short) (x << 8 | z << 4 | y));
                if (state.getValue(BlockStateProperties.LIT)) inside.add(chunk.getPos().getBlockAt(x, 80 + y, z));
            }
        }
        var storage = (PolymerChunkSectionStorage) section;
        eq("full-palette-index", new HashSet<>(storage.polymer$getBackendSet()), expected);
        var actualInside = new HashSet<BlockPos>();
        storage.polymer$lightInsideIterator(net.minecraft.core.SectionPos.of(chunk.getPos(), 5))
                .forEachRemaining(pos -> actualInside.add(pos.immutable()));
        eq("full-inside-index", actualInside, inside);
        eq("full-forced-light-index", storage.polymer$hasLights(), !inside.isEmpty());
    }

    private static void eq(String label, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        RESULTS.add(Map.of("assertion", label, "expected", expected.toString(), "actual", actual.toString()));
    }

    private static void save(String operation, Throwable failure) {
        try {
            var result = new HashMap<String, Object>();
            result.put("operation", operation); result.put("assertions", RESULTS);
            result.put("created", created); result.put("attached", attached); result.put("detached", detached);
            result.put("updates", updates); result.put("nbtUpdates", nbtUpdates);
            if (observedChunk != null) {
                result.put("observedIds", ((eu.pb4.polymer.virtualentity.impl.HolderAttachmentHolder) observedChunk)
                        .polymerVE$getHolders().stream().flatMap(attachment -> attachment.holder().getEntityIds().stream()).sorted().toList());
            }
            result.put("negativeControls", NEGATIVES);
            if (unloadedToken != null) result.put("unloadedActualDeltas", unloadedToken.changes().size());
            result.put("failure", failure == null ? null : failure.toString());
            Files.writeString(Path.of(System.getProperty("polymer.bulk.output"), operation + ".json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static final class FixtureHolder extends ElementHolder {
        private FixtureHolder() {
            var element = new BlockDisplayElement(Blocks.EMERALD_BLOCK.defaultBlockState());
            this.addElement(element);
        }
        @Override protected void onAttachmentSet(HolderAttachment current, HolderAttachment old) { attached++; }
        @Override protected void onAttachmentRemoved(HolderAttachment old) { detached++; }
        @Override protected void onTick() { ticks++; }
        @Override public void notifyUpdate(HolderAttachment.UpdateType type) {
            if (throwUpdate) throw new IllegalStateException("deliberate-update-failure");
            if (type == BlockBoundAttachment.BLOCK_STATE_UPDATE) updates++;
            if (type == BlockBoundAttachment.BLOCK_ENTITY_UPDATE) nbtUpdates++;
        }
    }
}
