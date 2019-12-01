package net.gegy1000.terrarium.server.world.pipeline.composer.structure;

import net.gegy1000.gengen.api.ChunkPopulationWriter;
import net.gegy1000.gengen.api.ChunkPrimeWriter;
import net.gegy1000.gengen.api.CubicPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public interface StructureComposer {
    void prepareStructures(CubicPos pos);

    void primeStructures(CubicPos pos, ChunkPrimeWriter writer);

    void populateStructures(CubicPos pos, ChunkPopulationWriter writer);

    boolean isInsideStructure(World world, String name, BlockPos pos);

    @Nullable
    BlockPos getClosestStructure(World world, String name, BlockPos pos, boolean findUnexplored);
}
