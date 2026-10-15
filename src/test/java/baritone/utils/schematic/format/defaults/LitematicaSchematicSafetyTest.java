/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.schematic.format.defaults;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LitematicaSchematicSafetyTest {

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void calculatesOrdinaryRegionVolume() {
        assertEquals(6_000L, LitematicaSchematic.getVolume(region(10, 20, 30)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroSizedRegion() {
        LitematicaSchematic.getVolume(region(0, 10, 10));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRegionAboveSafeAllocationLimit() {
        LitematicaSchematic.getVolume(region(256, 256, 256));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooManyColumnArraysEvenWhenVolumeIsBelowLimit() {
        LitematicaSchematic.getVolume(region(1024, 1, 1024));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSubregionExtentOverflow() {
        LitematicaSchematic.validateExtent(Integer.MAX_VALUE - 1, 3, "x");
    }

    @Test
    public void acceptsRepresentableSubregionExtent() {
        LitematicaSchematic.validateExtent(100, 25, "z");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAggregateVolumeAcrossRegions() {
        CompoundTag largeRegionA = region(512, 512, 20);
        CompoundTag largeRegionB = region(512, 512, 20);
        LitematicaSchematic.validateSchematicBudget(new CompoundTag[]{largeRegionA, largeRegionB});
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsHugeCompositeBoundsFromSmallDistantRegions() {
        CompoundTag first = region(1, 1, 1);
        CompoundTag distant = region(1, 1, 1);
        CompoundTag position = new CompoundTag();
        position.putInt("x", 10_000_000);
        position.putInt("y", 0);
        position.putInt("z", 0);
        distant.put("Position", position);

        LitematicaSchematic.validateSchematicBudget(new CompoundTag[]{first, distant});
    }


    @Test(expected = IllegalArgumentException.class)
    public void rejectsSchematicWithoutRegions() {
        LitematicaSchematic.validateSchematicBudget(new CompoundTag[0]);
    }

    @Test
    public void acceptsRegionCountAtLimit() {
        assertEquals(4_096, LitematicaSchematic.validateRegionCount(4_096));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooManyRegions() {
        LitematicaSchematic.validateRegionCount(4_097);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroRegions() {
        LitematicaSchematic.validateRegionCount(0);
    }

    @Test
    public void decodesMinimalOneBlockLitematicaSchematic() {
        LitematicaSchematic schematic = new LitematicaSchematic(singleBlockSchematic(0L));

        assertEquals(1, schematic.widthX());
        assertEquals(1, schematic.heightY());
        assertEquals(1, schematic.lengthZ());
        assertEquals(Blocks.STONE.defaultBlockState(), schematic.getDirect(0, 0, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidPackedPaletteIndexDuringDecode() {
        new LitematicaSchematic(singleBlockSchematic(3L));
    }

    @Test
    public void acceptsPaletteNoLargerThanRegionVolume() {
        assertEquals(1, LitematicaSchematic.validatePaletteSize(1, 1L));
        assertEquals(2, LitematicaSchematic.validatePaletteSize(2, 2L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPaletteLargerThanRegionVolume() {
        LitematicaSchematic.validatePaletteSize(2, 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyPaletteSizeOrRegionVolume() {
        LitematicaSchematic.validatePaletteSize(0, 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPaletteWithoutPositiveRegionVolume() {
        LitematicaSchematic.validatePaletteSize(1, 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyBlockPalette() {
        LitematicaSchematic.getBlockList(new ListTag());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownBlockStateProperty() {
        CompoundTag properties = new CompoundTag();
        properties.putString("not_a_real_property", "true");
        LitematicaSchematic.getBlockState(Blocks.STONE, properties);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidBlockStatePropertyValue() {
        CompoundTag properties = new CompoundTag();
        properties.putString("axis", "sideways");
        LitematicaSchematic.getBlockState(Blocks.OAK_LOG, properties);
    }


    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedPackedBlockStateArray() {
        new LitematicaSchematic.LitematicaBitArray(2, 64, new long[1]);
    }

    @Test
    public void calculatesPackedLongArrayLength() {
        assertEquals(2, LitematicaSchematic.LitematicaBitArray.getRequiredLongArrayCount(5, 13));
        assertEquals(5, LitematicaSchematic.LitematicaBitArray.getRequiredLongArrayCount(5, 64));
    }

    @Test
    public void acceptsValidPaletteIndex() {
        assertEquals(2, LitematicaSchematic.validatePaletteIndex(2, 3, 12));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPaletteIndexOutsidePalette() {
        LitematicaSchematic.validatePaletteIndex(3, 3, 12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnrepresentableAbsoluteDimension() {
        LitematicaSchematic.getVolume(region(Integer.MIN_VALUE, 1, 1));
    }

    @Test
    public void calculatesMinimumForNegativeRegionSize() {
        CompoundTag subregion = region(-5, 4, 4);
        CompoundTag position = new CompoundTag();
        position.putInt("x", 10);
        position.putInt("y", 0);
        position.putInt("z", 0);
        subregion.put("Position", position);

        assertEquals(6, LitematicaSchematic.getMinOfSubregion(subregion, "x"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOverflowingRegionEndpoint() {
        CompoundTag subregion = region(1, 1, 1);
        CompoundTag position = new CompoundTag();
        position.putInt("x", Integer.MAX_VALUE);
        position.putInt("y", 0);
        position.putInt("z", 0);
        subregion.put("Position", position);

        LitematicaSchematic.getMinOfSubregion(subregion, "x");
    }

    @Test
    public void calculatesSchematicOffsetWithoutOverflow() {
        assertEquals(8, LitematicaSchematic.getCheckedOffset(10, 2, "x"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOverflowingSchematicOffset() {
        LitematicaSchematic.getCheckedOffset(Integer.MAX_VALUE, Integer.MIN_VALUE, "x");
    }

    private static CompoundTag singleBlockSchematic(long packedBlockStateWord) {
        CompoundTag subregion = region(1, 1, 1);
        CompoundTag paletteEntry = new CompoundTag();
        paletteEntry.putString("Name", "minecraft:stone");
        paletteEntry.put("Properties", new CompoundTag());

        ListTag palette = new ListTag();
        palette.add(paletteEntry);
        subregion.put("BlockStatePalette", palette);
        subregion.putLongArray("BlockStates", new long[]{packedBlockStateWord});

        CompoundTag regions = new CompoundTag();
        regions.put("single", subregion);
        CompoundTag schematic = new CompoundTag();
        schematic.put("Regions", regions);
        return schematic;
    }

    private static CompoundTag region(int x, int y, int z) {
        CompoundTag size = new CompoundTag();
        size.putInt("x", x);
        size.putInt("y", y);
        size.putInt("z", z);

        CompoundTag position = new CompoundTag();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);

        CompoundTag subregion = new CompoundTag();
        subregion.put("Size", size);
        subregion.put("Position", position);
        return subregion;
    }
}
