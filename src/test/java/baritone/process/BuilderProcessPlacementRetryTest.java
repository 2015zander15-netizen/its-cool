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

package baritone.process;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderProcessPlacementRetryTest {

    @Test
    public void retryDelayIncreasesForEarlyAttempts() {
        assertEquals(4, BuilderProcess.placementRetryDelayTicks(1));
        assertEquals(8, BuilderProcess.placementRetryDelayTicks(2));
        assertEquals(16, BuilderProcess.placementRetryDelayTicks(3));
        assertEquals(32, BuilderProcess.placementRetryDelayTicks(4));
    }

    @Test
    public void retryDelayRemainsBoundedForLaterAttempts() {
        assertEquals(32, BuilderProcess.placementRetryDelayTicks(5));
        assertEquals(32, BuilderProcess.placementRetryDelayTicks(100));
    }

    @Test(expected = IllegalArgumentException.class)
    public void retryDelayRejectsZeroAttemptNumber() {
        BuilderProcess.placementRetryDelayTicks(0);
    }

    @Test
    public void inventoryHeuristicAcceptsDifferentOrientationsOfSameBlock() {
        BlockState northFacing = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH);
        BlockState eastFacing = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST);

        assertTrue(BuilderProcess.itemCouldPotentiallyPlace(northFacing, eastFacing));
    }

    @Test
    public void inventoryHeuristicRejectsDifferentBlockTypes() {
        assertFalse(BuilderProcess.itemCouldPotentiallyPlace(
                Blocks.STONE.defaultBlockState(),
                Blocks.OAK_STAIRS.defaultBlockState()
        ));
    }

}
