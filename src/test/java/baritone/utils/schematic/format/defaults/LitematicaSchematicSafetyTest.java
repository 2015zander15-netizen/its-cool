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

import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LitematicaSchematicSafetyTest {

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
