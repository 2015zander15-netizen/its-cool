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

import baritone.testutil.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LitematicaSchematicSafetyTest {

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        MinecraftTestBootstrap.ensureInitialized();
    }

    @Test
    public void paletteBitWidthUsesExactIntegerBoundaries() {
        assertEquals(2, LitematicaSchematic.getBitsPerBlock(1));
        assertEquals(2, LitematicaSchematic.getBitsPerBlock(2));
        assertEquals(2, LitematicaSchematic.getBitsPerBlock(4));
        assertEquals(3, LitematicaSchematic.getBitsPerBlock(5));
        assertEquals(3, LitematicaSchematic.getBitsPerBlock(8));
        assertEquals(4, LitematicaSchematic.getBitsPerBlock(9));
        assertEquals(5, LitematicaSchematic.getBitsPerBlock(32));
        assertEquals(16, LitematicaSchematic.getBitsPerBlock(65_536));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyPaletteBitWidth() {
        LitematicaSchematic.getBitsPerBlock(0);
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
    public void rejectsIntermediateArrayOverheadForSkinnyRegion() {
        LitematicaSchematic.getVolume(region(262_144, 1, 1));
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
    public void cleanSchematicHasNoUnsupportedPayloadWarning() {
        LitematicaSchematic schematic = new LitematicaSchematic(singleBlockSchematic(0L));

        org.junit.Assert.assertNull(schematic.getUnsupportedPayloadWarning());
    }

    @Test
    public void reportsBlockEntityAndEntityPayloadCounts() {
        CompoundTag nbt = singleBlockSchematic(0L);
        CompoundTag region = nbt.getCompoundOrEmpty("Regions").getCompoundOrEmpty("single");
        ListTag tileEntities = new ListTag();
        tileEntities.add(new CompoundTag());
        tileEntities.add(new CompoundTag());
        ListTag entities = new ListTag();
        entities.add(new CompoundTag());
        region.put("TileEntities", tileEntities);
        region.put("Entities", entities);

        String warning = new LitematicaSchematic(nbt).getUnsupportedPayloadWarning();

        org.junit.Assert.assertNotNull(warning);
        org.junit.Assert.assertTrue(warning.contains("2 block-entity record(s)"));
        org.junit.Assert.assertTrue(warning.contains("1 entity record(s)"));
        org.junit.Assert.assertTrue(warning.contains("does not restore block-entity NBT/inventories"));
    }

    @Test
    public void reportsModernBlockEntitiesPayloadsToo() {
        CompoundTag nbt = singleBlockSchematic(0L);
        CompoundTag region = nbt.getCompoundOrEmpty("Regions").getCompoundOrEmpty("single");
        ListTag blockEntities = new ListTag();
        blockEntities.add(new CompoundTag());
        region.put("BlockEntities", blockEntities);

        String warning = new LitematicaSchematic(nbt).getUnsupportedPayloadWarning();

        org.junit.Assert.assertNotNull(warning);
        org.junit.Assert.assertTrue(warning.contains("1 block-entity record(s)"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonListBlockEntityPayload() {
        CompoundTag nbt = singleBlockSchematic(0L);
        CompoundTag region = nbt.getCompoundOrEmpty("Regions").getCompoundOrEmpty("single");
        region.putString("TileEntities", "not_an_nbt_list");

        new LitematicaSchematic(nbt);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonListEntityPayload() {
        CompoundTag nbt = singleBlockSchematic(0L);
        CompoundTag region = nbt.getCompoundOrEmpty("Regions").getCompoundOrEmpty("single");
        region.putString("Entities", "not_an_nbt_list");

        new LitematicaSchematic(nbt);
    }

    @Test
    public void acceptsLitematicaVersion7WithSubVersionAndModernRegionFields() {
        CompoundTag nbt = singleBlockSchematic(0L);
        nbt.putInt("Version", 7);
        nbt.putInt("SubVersion", 1);
        nbt.putInt("MinecraftDataVersion", 3953);
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", "version-seven-test");
        metadata.putInt("RegionCount", 1);
        nbt.put("Metadata", metadata);
        CompoundTag region = nbt.getCompoundOrEmpty("Regions").getCompoundOrEmpty("single");
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());

        LitematicaSchematic schematic = new LitematicaSchematic(nbt);

        assertEquals(Blocks.STONE.defaultBlockState(), schematic.getDirect(0, 0, 0));
    }

    @Test
    public void acceptsAllKnownLitematicaCoreFormatVersions() {
        for (int version = 1; version <= 7; version++) {
            CompoundTag nbt = singleBlockSchematic(0L);
            nbt.putInt("Version", version);
            if (version >= 6) {
                nbt.putInt("SubVersion", version == 7 ? 1 : 0);
            }

            LitematicaSchematic schematic = new LitematicaSchematic(nbt);

            assertEquals("Failed to decode Litematica format version " + version,
                    Blocks.STONE.defaultBlockState(), schematic.getDirect(0, 0, 0));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedFutureLitematicaVersionClearly() {
        CompoundTag nbt = singleBlockSchematic(0L);
        nbt.putInt("Version", 8);
        new LitematicaSchematic(nbt);
    }

    @Test
    public void decodesMinimalOneBlockLitematicaSchematic() {
        LitematicaSchematic schematic = new LitematicaSchematic(singleBlockSchematic(0L));

        assertEquals(1, schematic.widthX());
        assertEquals(1, schematic.heightY());
        assertEquals(1, schematic.lengthZ());
        assertEquals(Blocks.STONE.defaultBlockState(), schematic.getDirect(0, 0, 0));
    }

    @Test
    public void decodesTwoBlockRegionInXOrder() {
        LitematicaSchematic schematic = new LitematicaSchematic(twoBlockSchematic());

        assertEquals(2, schematic.widthX());
        assertEquals(1, schematic.heightY());
        assertEquals(1, schematic.lengthZ());
        assertEquals(Blocks.STONE.defaultBlockState(), schematic.getDirect(0, 0, 0));
        assertEquals(Blocks.DIRT.defaultBlockState(), schematic.getDirect(1, 0, 0));
    }

    @Test
    public void decodesPackedEntriesThatCrossLongWordBoundary() {
        int bitsPerEntry = 5;
        int[] values = new int[20];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i * 7 + 3) & 31;
        }
        long[] packed = packEntries(bitsPerEntry, values);
        LitematicaSchematic.LitematicaBitArray bitArray =
                new LitematicaSchematic.LitematicaBitArray(bitsPerEntry, values.length, packed);

        for (int i = 0; i < values.length; i++) {
            assertEquals("packed entry " + i, values[i], bitArray.getAt(i));
        }
    }

    @Test
    public void decodesSeparatedSubregionsAtTheirCompositeOffsets() {
        CompoundTag stoneRegion = region(1, 1, 1);
        addSingleBlockPalette(stoneRegion, "minecraft:stone");
        CompoundTag dirtRegion = region(1, 1, 1);
        CompoundTag dirtPosition = new CompoundTag();
        dirtPosition.putInt("x", 2);
        dirtPosition.putInt("y", 0);
        dirtPosition.putInt("z", 0);
        dirtRegion.put("Position", dirtPosition);
        addSingleBlockPalette(dirtRegion, "minecraft:dirt");

        CompoundTag regions = new CompoundTag();
        regions.put("stone", stoneRegion);
        regions.put("dirt", dirtRegion);
        CompoundTag nbt = new CompoundTag();
        nbt.put("Regions", regions);

        LitematicaSchematic schematic = new LitematicaSchematic(nbt);
        assertEquals(3, schematic.widthX());
        assertEquals(Blocks.STONE.defaultBlockState(), schematic.getDirect(0, 0, 0));
        assertEquals(Blocks.DIRT.defaultBlockState(), schematic.getDirect(2, 0, 0));
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

    @Test
    public void acceptsUnusedPaletteEntriesBeyondRegionVolume() {
        assertEquals(3, LitematicaSchematic.validatePaletteSize(3, 1L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPaletteAboveSafeEntryLimit() {
        LitematicaSchematic.validatePaletteSize(65_537, 65_537L);
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
    public void rejectsPaletteEntryWithWrongTagType() {
        ListTag palette = new ListTag();
        palette.add(IntTag.valueOf(3));
        LitematicaSchematic.getBlockList(palette);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyBlockPalette() {
        LitematicaSchematic.getBlockList(new ListTag());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPropertiesFieldWithWrongNbtType() {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", "minecraft:stone");
        entry.putString("Properties", "not_a_compound");
        ListTag palette = new ListTag();
        palette.add(entry);

        LitematicaSchematic.getBlockList(palette);
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

    private static long[] packEntries(int bitsPerEntry, int[] values) {
        long bitCount = (long) bitsPerEntry * values.length;
        long[] words = new long[(int) ((bitCount + 63L) / 64L)];
        for (int i = 0; i < values.length; i++) {
            long bitOffset = (long) i * bitsPerEntry;
            int wordIndex = (int) (bitOffset >>> 6);
            int startBit = (int) (bitOffset & 63L);
            long value = values[i] & ((1L << bitsPerEntry) - 1L);
            words[wordIndex] |= value << startBit;
            int bitsInFirstWord = Long.SIZE - startBit;
            if (bitsInFirstWord < bitsPerEntry) {
                words[wordIndex + 1] |= value >>> bitsInFirstWord;
            }
        }
        return words;
    }

    private static CompoundTag twoBlockSchematic() {
        CompoundTag subregion = region(2, 1, 1);
        ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        stone.put("Properties", new CompoundTag());
        palette.add(stone);
        CompoundTag dirt = new CompoundTag();
        dirt.putString("Name", "minecraft:dirt");
        dirt.put("Properties", new CompoundTag());
        palette.add(dirt);

        subregion.put("BlockStatePalette", palette);
        // Minimum palette width is 2 bits; [stone, dirt] is encoded as 0b0100.
        subregion.putLongArray("BlockStates", new long[]{1L << 2});
        CompoundTag regions = new CompoundTag();
        regions.put("two_blocks", subregion);
        CompoundTag schematic = new CompoundTag();
        schematic.put("Regions", regions);
        return schematic;
    }

    private static void addSingleBlockPalette(CompoundTag subregion, String blockName) {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", blockName);
        entry.put("Properties", new CompoundTag());
        ListTag palette = new ListTag();
        palette.add(entry);
        subregion.put("BlockStatePalette", palette);
        subregion.putLongArray("BlockStates", new long[]{0L});
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
