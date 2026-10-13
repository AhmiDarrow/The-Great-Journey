package com.ninjacat.greatjourney.clanlands.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.ninjacat.greatjourney.clanlands.world.StructureRules;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;

/** Surface structures do not start in the Clan lands (see {@link StructureRules}). */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorMixin {
    @WrapMethod(method = "tryGenerateStructure")
    private boolean clanlands$notInTheClanLands(StructureSet.StructureSelectionEntry entry, StructureManager structures,
                                                RegistryAccess registries, RandomState random, StructureTemplateManager templates,
                                                long seed, ChunkAccess chunk, ChunkPos pos, SectionPos section, Operation<Boolean> original) {
        if (StructureRules.blocked((ChunkGenerator) (Object) this, entry.structure().value(), pos)) return false;
        return original.call(entry, structures, registries, random, templates, seed, chunk, pos, section);
    }
}
