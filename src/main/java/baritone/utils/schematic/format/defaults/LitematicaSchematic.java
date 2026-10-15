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

import baritone.api.schematic.CompositeSchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.apache.commons.lang3.Validate;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Optional;

/**
 * Based on EmersonDove's work
 * <a href="https://github.com/cabaletta/baritone/pull/2544">...</a>
 *
 * @author rycbar
 * @since 22.09.2022
 */
public final class LitematicaSchematic extends CompositeSchematic implements IStaticSchematic {

    // The decoder materializes a three-dimensional BlockState reference array.
    // Keep malformed or enormous inputs from exhausting the client heap.
    private static final int MAX_REGION_COUNT = 4_096;
    private static final int MAX_PALETTE_ENTRIES = 65_536;
    private static final long MAX_REGION_VOLUME = 8_388_608L;
    // BlockState[][][] allocates one leaf array per (x, z) column; cap their count too.
    private static final long MAX_REGION_COLUMN_ARRAYS = 262_144L;
    private static final long MAX_SCHEMATIC_VOLUME = 8_388_608L;
    private static final long MAX_SCHEMATIC_COLUMN_ARRAYS = 262_144L;

    private long unsupportedBlockEntityCount;
    private long unsupportedEntityCount;

    /**
     * @param nbtTagCompound a decompressed file stream aka nbt data.
     * @param rotated        if the schematic is rotated by 90°.
     */
    public LitematicaSchematic(CompoundTag nbt) {
        super(0, 0, 0);
        fillInSchematic(nbt);
    }

    /**
     * @return Array of subregion tags.
     */
    static int validateRegionCount(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Litematica schematic contains no regions");
        }
        if (count > MAX_REGION_COUNT) {
            throw new IllegalArgumentException("Litematica schematic has too many regions: "
                    + count + " (maximum " + MAX_REGION_COUNT + ")");
        }
        return count;
    }

    private static CompoundTag[] getRegions(CompoundTag nbt) {
        CompoundTag regions = nbt.getCompoundOrEmpty("Regions");
        validateRegionCount(regions.keySet().size());
        return regions.keySet().stream()
                .map(regions::getCompoundOrEmpty)
                .toArray(CompoundTag[]::new);
    }

    /**
     * Gets both ends from a region box for a given axis and returns the lower one.
     *
     * @param s axis that should be read.
     * @return the lower coord of the requested axis.
     */
    static int getMinOfSubregion(CompoundTag subReg, String s) {
        int position = subReg.getCompoundOrEmpty("Position").getIntOr(s, 0);
        int size = subReg.getCompoundOrEmpty("Size").getIntOr(s, 0);
        long oppositeEnd = (long) position + size + 1L;
        if (oppositeEnd < Integer.MIN_VALUE || oppositeEnd > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Litematica subregion coordinate overflows the int range on axis " + s);
        }
        return Math.min(position, (int) oppositeEnd);
    }

    /**
     * @param blockStatePalette List of all different block types used in the schematic.
     * @return Array of BlockStates.
     */
    static int validatePaletteSize(int paletteSize, long regionVolume) {
        if (paletteSize <= 0) {
            throw new IllegalArgumentException("Litematica subregion has an empty block-state palette");
        }
        if (regionVolume <= 0 || paletteSize > regionVolume) {
            throw new IllegalArgumentException("Litematica palette has " + paletteSize
                    + " entries but region volume is " + regionVolume);
        }
        if (paletteSize > MAX_PALETTE_ENTRIES) {
            throw new IllegalArgumentException("Litematica block-state palette exceeds the safe entry limit: "
                    + paletteSize + " entries (maximum " + MAX_PALETTE_ENTRIES + ")");
        }
        return paletteSize;
    }

    static BlockState[] getBlockList(ListTag blockStatePalette) {
        if (blockStatePalette.isEmpty()) {
            throw new IllegalArgumentException("Litematica subregion has an empty block-state palette");
        }
        if (blockStatePalette.size() > MAX_PALETTE_ENTRIES) {
            throw new IllegalArgumentException("Litematica block-state palette exceeds the safe entry limit: "
                    + blockStatePalette.size() + " entries (maximum " + MAX_PALETTE_ENTRIES + ")");
        }

        BlockState[] blockList = new BlockState[blockStatePalette.size()];
        for (int i = 0; i < blockStatePalette.size(); i++) {
            Object entry = blockStatePalette.get(i);
            if (!(entry instanceof CompoundTag)) {
                throw new IllegalArgumentException("Litematica block-state palette entry " + i
                        + " is not a compound tag");
            }
            CompoundTag paletteEntry = (CompoundTag) entry;
            String blockName = paletteEntry.getStringOr("Name", "");
            final Identifier blockId;
            try {
                blockId = Identifier.parse(blockName);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid block identifier in Litematica palette: " + blockName, exception);
            }
            if (!BuiltInRegistries.BLOCK.containsKey(blockId)) {
                throw new IllegalArgumentException("Unknown block in Litematica palette: " + blockId);
            }
            Block block = BuiltInRegistries.BLOCK.getValue(blockId);
            if (block == null) {
                throw new IllegalArgumentException("Unknown block in Litematica palette: " + blockId);
            }
            Tag propertiesTag = paletteEntry.get("Properties");
            if (propertiesTag != null && !(propertiesTag instanceof CompoundTag)) {
                throw new IllegalArgumentException("Properties for Litematica palette entry "
                        + blockId + " must be an NBT compound");
            }
            CompoundTag properties = propertiesTag instanceof CompoundTag
                    ? (CompoundTag) propertiesTag
                    : new CompoundTag();
            blockList[i] = getBlockState(block, properties);
        }
        return blockList;
    }

    /**
     * @param block      block.
     * @param properties List of Properties the block has.
     * @return A blockState.
     */
    static BlockState getBlockState(Block block, CompoundTag properties) {
        BlockState blockState = block.defaultBlockState();

        for (Object key : properties.keySet()) {
            String propertyName = (String) key;
            Property<?> property = block.getStateDefinition().getProperty(propertyName);
            if (property == null) {
                throw new IllegalArgumentException("Unknown block-state property "
                        + propertyName + " for Litematica block " + BuiltInRegistries.BLOCK.getKey(block));
            }
            String propertyValue = properties.getStringOr(propertyName, "");
            blockState = setPropertyValue(blockState, property, propertyValue);
        }
        return blockState;
    }

    /**
     * @author Emerson
     */
    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        if (parsed.isPresent()) {
            return state.setValue(property, parsed.get());
        } else {
            throw new IllegalArgumentException("Invalid value for property " + property);
        }
    }

    /**
     * @param amountOfBlockTypes amount of block types in the schematic.
     * @return amount of bits used to encode a block.
     */
    static int getBitsPerBlock(int amountOfBlockTypes) {
        if (amountOfBlockTypes <= 0) {
            throw new IllegalArgumentException("Litematica palette must contain at least one block type");
        }
        // Exact integer ceiling(log2(n)); avoid floating-point rounding at powers of two.
        int bits = amountOfBlockTypes <= 1
                ? 0
                : Integer.SIZE - Integer.numberOfLeadingZeros(amountOfBlockTypes - 1);
        return Math.max(2, bits);
    }

    /**
     * Calculates the volume of the subregion. As size can be a negative value we take the absolute value of the
     * multiplication as the volume still holds a positive amount of blocks.
     *
     * @return the volume of the subregion.
     */
    static long getVolume(CompoundTag subReg) {
        CompoundTag size = subReg.getCompoundOrEmpty("Size");
        long x = getAbsoluteDimension(size, "x");
        long y = getAbsoluteDimension(size, "y");
        long z = getAbsoluteDimension(size, "z");

        if (x == 0 || y == 0 || z == 0) {
            throw new IllegalArgumentException("Litematica subregion must have non-zero dimensions");
        }

        final long volume;
        try {
            volume = Math.multiplyExact(Math.multiplyExact(x, y), z);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Litematica subregion volume overflows a 64-bit integer", e);
        }

        // The decoder materializes a 3D reference array, so an int-sized volume is not necessarily safe in memory.
        if (volume > MAX_REGION_VOLUME) {
            throw new IllegalArgumentException("Litematica subregion exceeds the safe in-memory decoding limit: "
                    + volume + " blocks (maximum " + MAX_REGION_VOLUME + ")");
        }

        final long nestedArrayCount;
        try {
            // new BlockState[x][z][y] allocates x intermediate arrays plus x*z leaf arrays.
            nestedArrayCount = Math.addExact(x, Math.multiplyExact(x, z));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Litematica nested array count overflows a 64-bit integer", e);
        }
        if (nestedArrayCount > MAX_REGION_COLUMN_ARRAYS) {
            throw new IllegalArgumentException("Litematica subregion would allocate too many nested arrays: "
                    + nestedArrayCount + " (maximum " + MAX_REGION_COLUMN_ARRAYS + ")");
        }
        return volume;
    }

    /**
     * Returns the absolute region dimension, rejecting the one int value whose absolute value
     * cannot be represented as a positive int for the backing array dimensions.
     */
    private static int getAbsoluteDimension(CompoundTag size, String axis) {
        int dimension = size.getIntOr(axis, 0);
        if (dimension == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Invalid Litematica subregion dimension for axis " + axis);
        }
        return Math.abs(dimension);
    }

    /**
     * @param s axis.
     * @return the lowest coordinate of that axis of the schematic.
     */
    private static int getMinOfSchematic(CompoundTag[] regions, String axis) {
        if (regions.length == 0) {
            throw new IllegalArgumentException("Litematica schematic contains no regions");
        }
        int minimum = Integer.MAX_VALUE;
        for (CompoundTag subRegion : regions) {
            minimum = Math.min(minimum, getMinOfSubregion(subRegion, axis));
        }
        return minimum;
    }

    /**
     * Validates all region sizes and the aggregate memory budget before decoding any 3D arrays.
     */
    static void validateSchematicBudget(CompoundTag[] regions) {
        if (regions.length == 0) {
            throw new IllegalArgumentException("Litematica schematic contains no regions");
        }
        long totalVolume = 0;
        long totalColumnArrays = 0;
        int minimumX = getMinOfSchematic(regions, "x");
        int minimumY = getMinOfSchematic(regions, "y");
        int minimumZ = getMinOfSchematic(regions, "z");
        long boundsX = 0;
        long boundsY = 0;
        long boundsZ = 0;

        for (CompoundTag subRegion : regions) {
            long volume = getVolume(subRegion);
            CompoundTag size = subRegion.getCompoundOrEmpty("Size");
            long x = getAbsoluteDimension(size, "x");
            long y = getAbsoluteDimension(size, "y");
            long z = getAbsoluteDimension(size, "z");
            int offsetX = getCheckedOffset(getMinOfSubregion(subRegion, "x"), minimumX, "x");
            int offsetY = getCheckedOffset(getMinOfSubregion(subRegion, "y"), minimumY, "y");
            int offsetZ = getCheckedOffset(getMinOfSubregion(subRegion, "z"), minimumZ, "z");
            validateExtent(offsetX, (int) x, "x");
            validateExtent(offsetY, (int) y, "y");
            validateExtent(offsetZ, (int) z, "z");
            try {
                totalVolume = Math.addExact(totalVolume, volume);
                // Include both x intermediate arrays and x*z leaf arrays for the 3D reference array.
                long regionArrayCount = Math.addExact(x, Math.multiplyExact(x, z));
                totalColumnArrays = Math.addExact(totalColumnArrays, regionArrayCount);
                boundsX = Math.max(boundsX, Math.addExact((long) offsetX, x));
                boundsY = Math.max(boundsY, Math.addExact((long) offsetY, y));
                boundsZ = Math.max(boundsZ, Math.addExact((long) offsetZ, z));
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Litematica schematic aggregate size overflows a 64-bit integer", exception);
            }
            if (totalVolume > MAX_SCHEMATIC_VOLUME) {
                throw new IllegalArgumentException("Litematica schematic exceeds the safe total volume: "
                        + totalVolume + " blocks (maximum " + MAX_SCHEMATIC_VOLUME + ")");
            }
            if (totalColumnArrays > MAX_SCHEMATIC_COLUMN_ARRAYS) {
                throw new IllegalArgumentException("Litematica schematic would allocate too many column arrays: "
                        + totalColumnArrays + " (maximum " + MAX_SCHEMATIC_COLUMN_ARRAYS + ")");
            }
        }

        final long boundsVolume;
        try {
            boundsVolume = Math.multiplyExact(Math.multiplyExact(boundsX, boundsY), boundsZ);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Litematica composite bounding-box volume overflows a 64-bit integer", exception);
        }
        if (boundsVolume > MAX_SCHEMATIC_VOLUME) {
            throw new IllegalArgumentException("Litematica schematic's composite bounding box is too large to scan safely: "
                    + boundsVolume + " blocks (maximum " + MAX_SCHEMATIC_VOLUME + ")");
        }
    }

    /**
     * Converts an absolute coordinate into a schematic-local offset without allowing int wraparound.
     */
    static int getCheckedOffset(int coordinate, int minimumCoordinate, String axis) {
        long offset = (long) coordinate - minimumCoordinate;
        if (offset < 0 || offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Litematica subregion offset is out of range on axis " + axis + ": " + offset);
        }
        return (int) offset;
    }

    /**
     * Validates that a local schematic offset plus its positive dimension remains representable.
     * CompositeSchematic uses int addition when computing its overall extents.
     */
    static void validateExtent(int offset, int dimension, String axis) {
        long end = (long) offset + dimension;
        if (offset < 0 || dimension <= 0 || end > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Litematica subregion extent is out of range on axis " + axis
                    + ": offset=" + offset + ", dimension=" + dimension);
        }
    }

    /**
     * Returns a warning when the schematic contains payloads that ordinary block placement does not restore.
     * A null result means there are no block-entity or entity records in the decoded Litematica regions.
     */
    public String getUnsupportedPayloadWarning() {
        if (unsupportedBlockEntityCount == 0 && unsupportedEntityCount == 0) {
            return null;
        }
        StringBuilder warning = new StringBuilder("This Litematica contains ");
        if (unsupportedBlockEntityCount > 0) {
            warning.append(unsupportedBlockEntityCount)
                    .append(" block-entity record(s), including possible container contents");
        }
        if (unsupportedBlockEntityCount > 0 && unsupportedEntityCount > 0) {
            warning.append(" and ");
        }
        if (unsupportedEntityCount > 0) {
            warning.append(unsupportedEntityCount).append(" entity record(s)");
        }
        warning.append(". Baritone's builder places block states only; it does not restore block-entity NBT/inventories or spawn schematic entities.");
        return warning.toString();
    }

    static long countListRecords(CompoundTag compound, String key) {
        Tag value = compound.get(key);
        if (value == null) {
            return 0L;
        }
        if (!(value instanceof ListTag)) {
            throw new IllegalArgumentException("Litematica " + key + " payload must be an NBT list");
        }
        return ((ListTag) value).size();
    }

    /**
     * reads the file data.
     */
    private void fillInSchematic(CompoundTag nbt) {
        CompoundTag[] regions = getRegions(nbt);
        validateSchematicBudget(regions);
        unsupportedBlockEntityCount = 0L;
        unsupportedEntityCount = 0L;
        for (CompoundTag region : regions) {
            // Litematica stores block-entity and entity payloads alongside each region's block palette.
            // Baritone's schematic builder currently decodes block states only, so report these records
            // rather than silently making the build appear fully restored.
            unsupportedBlockEntityCount = Math.addExact(
                    unsupportedBlockEntityCount,
                    countListRecords(region, "TileEntities") + countListRecords(region, "BlockEntities")
            );
            unsupportedEntityCount = Math.addExact(
                    unsupportedEntityCount,
                    countListRecords(region, "Entities")
            );
        }
        Vec3i offsetMinCorner = new Vec3i(
                getMinOfSchematic(regions, "x"),
                getMinOfSchematic(regions, "y"),
                getMinOfSchematic(regions, "z")
        );
        for (CompoundTag subReg : regions) {
            long regionVolume = getVolume(subReg);
            ListTag usedBlockTypes = subReg.getListOrEmpty("BlockStatePalette");
            validatePaletteSize(usedBlockTypes.size(), regionVolume);
            BlockState[] blockList = getBlockList(usedBlockTypes);

            int bitsPerBlock = getBitsPerBlock(usedBlockTypes.size());
            long[] blockStateArray = subReg.getLongArray("BlockStates").orElse(new long[0]);

            LitematicaBitArray bitArray = new LitematicaBitArray(bitsPerBlock, regionVolume, blockStateArray);
            writeSubregionIntoSchematic(subReg, offsetMinCorner, blockList, bitArray);
        }
    }

    static int validatePaletteIndex(int paletteIndex, int paletteSize, long entryIndex) {
        if (paletteIndex < 0 || paletteIndex >= paletteSize) {
            throw new IllegalArgumentException("Litematica BlockStates entry " + entryIndex
                    + " references missing palette index " + paletteIndex
                    + " (palette size " + paletteSize + ")");
        }
        return paletteIndex;
    }

    /**
     * Writes the file data in to the IBlockstate array.
     *
     * @param blockList list with the different block types used in the schematic.
     * @param bitArray  bit array that holds the placement pattern.
     */
    private void writeSubregionIntoSchematic(CompoundTag subReg, Vec3i offsetMinCorner, BlockState[] blockList, LitematicaBitArray bitArray) {
        int offsetX = getCheckedOffset(getMinOfSubregion(subReg, "x"), offsetMinCorner.getX(), "x");
        int offsetY = getCheckedOffset(getMinOfSubregion(subReg, "y"), offsetMinCorner.getY(), "y");
        int offsetZ = getCheckedOffset(getMinOfSubregion(subReg, "z"), offsetMinCorner.getZ(), "z");
        CompoundTag size = subReg.getCompoundOrEmpty("Size");
        int sizeX = getAbsoluteDimension(size, "x");
        int sizeY = getAbsoluteDimension(size, "y");
        int sizeZ = getAbsoluteDimension(size, "z");
        validateExtent(offsetX, sizeX, "x");
        validateExtent(offsetY, sizeY, "y");
        validateExtent(offsetZ, sizeZ, "z");
        BlockState[][][] states = new BlockState[sizeX][sizeZ][sizeY];
        int index = 0;
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int paletteIndex = validatePaletteIndex(bitArray.getAt(index), blockList.length, index);
                    states[x][z][y] = blockList[paletteIndex];
                    index++;
                }
            }
        }
        this.put(new StaticSchematic(states), offsetX, offsetY, offsetZ);
    }

    @Override
    public BlockState getDirect(int x, int y, int z) {
        return desiredState(x, y, z, null, Collections.emptyList());
    }

    /**
     * @author maruohon
     * Class from the Litematica mod by maruohon
     * Usage under LGPLv3 with the permission of the author.
     * <a href="https://github.com/maruohon/litematica">...</a>
     */
    static class LitematicaBitArray {
        /**
         * The long array that is used to store the data for this BitArray.
         */
        private final long[] longArray;
        /**
         * Number of bits a single entry takes up
         */
        private final int bitsPerEntry;
        /**
         * The maximum value for a single entry. This also works as a bitmask for a single entry.
         * For instance, if bitsPerEntry were 5, this value would be 31 (ie, {@code 0b00011111}).
         */
        private final long maxEntryValue;
        /**
         * Number of entries in this array (<b>not</b> the length of the long array that internally backs this array)
         */
        private final long arraySize;

        public LitematicaBitArray(int bitsPerEntryIn, long arraySizeIn, @Nullable long[] longArrayIn) {
            Validate.inclusiveBetween(1L, 32L, bitsPerEntryIn);
            if (arraySizeIn <= 0) {
                throw new IllegalArgumentException("Litematica bit array must contain at least one entry");
            }
            this.arraySize = arraySizeIn;
            this.bitsPerEntry = bitsPerEntryIn;
            this.maxEntryValue = (1L << bitsPerEntryIn) - 1L;

            int requiredLongCount = getRequiredLongArrayCount(bitsPerEntryIn, arraySizeIn);
            if (longArrayIn != null) {
                if (longArrayIn.length < requiredLongCount) {
                    throw new IllegalArgumentException("Truncated Litematica BlockStates array: expected at least "
                            + requiredLongCount + " longs, got " + longArrayIn.length);
                }
                this.longArray = longArrayIn;
            } else {
                this.longArray = new long[requiredLongCount];
            }
        }

        static int getRequiredLongArrayCount(int bitsPerEntry, long arraySize) {
            Validate.inclusiveBetween(1L, 32L, bitsPerEntry);
            if (arraySize <= 0) {
                throw new IllegalArgumentException("Litematica bit array must contain at least one entry");
            }
            try {
                long bitCount = Math.multiplyExact(arraySize, (long) bitsPerEntry);
                long longCount = Math.addExact(bitCount, 63L) / 64L;
                if (longCount > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Litematica BlockStates array is too large: " + longCount + " longs");
                }
                return (int) longCount;
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Litematica BlockStates bit count overflows a 64-bit integer", exception);
            }
        }

        public int getAt(long index) {
            Validate.inclusiveBetween(0L, this.arraySize - 1L, index);
            long startOffset = index * (long) this.bitsPerEntry;
            int startArrIndex = (int) (startOffset >> 6); // startOffset / 64
            int endArrIndex = (int) (((index + 1L) * (long) this.bitsPerEntry - 1L) >> 6);
            int startBitOffset = (int) (startOffset & 0x3F); // startOffset % 64

            if (startArrIndex == endArrIndex) {
                return (int) (this.longArray[startArrIndex] >>> startBitOffset & this.maxEntryValue);
            } else {
                int endOffset = 64 - startBitOffset;
                return (int) ((this.longArray[startArrIndex] >>> startBitOffset | this.longArray[endArrIndex] << endOffset) & this.maxEntryValue);
            }
        }

        public long size() {
            return this.arraySize;
        }
    }
}