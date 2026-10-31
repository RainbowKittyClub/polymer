package eu.pb4.polymer.core.impl;

import eu.pb4.polymer.common.api.PolymerBulkEdits;
import eu.pb4.polymer.core.impl.interfaces.PolymerChunkSectionStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Core's optional contribution to the common bulk-edit contract. */
public final class CoreBulkEdits implements PolymerBulkEdits.Participant {
    @Override
    public PolymerBulkEdits.Session prepare(LevelChunk chunk, Set<Integer> sections,
                                            Map<BlockPos, BlockState> proposed) {
        var lights = new HashMap<Integer, Boolean>();
        for (int y : sections) {
            var section = chunk.getSection(chunk.getSectionIndexFromSectionY(y));
            lights.put(y, ((PolymerChunkSectionStorage) section).polymer$requireLights());
        }
        return new PolymerBulkEdits.Session() {
            @Override
            public void rebuild() {
                lights.forEach((y, oldLight) -> PolymerSectionIndex.rebuild(
                        chunk.getSection(chunk.getSectionIndexFromSectionY(y)), oldLight));
            }

            @Override
            public void reconcile(List<PolymerBulkEdits.Change> changes) {
            }
        };
    }
}
