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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderProcessPlacementRetryTest {

    @Test
    public void pathFailureCountIncreasesOnlyForSameGoalWithoutProgress() {
        GoalBlock goal = new GoalBlock(1, 2, 3);
        GoalBlock differentGoal = new GoalBlock(4, 5, 6);

        assertEquals(1, BuilderProcess.nextGoalPathFailureCount(true, goal, goal, 0, false));
        assertEquals(2, BuilderProcess.nextGoalPathFailureCount(true, goal, goal, 1, false));
        assertEquals(1, BuilderProcess.nextGoalPathFailureCount(true, goal, differentGoal, 1, false));
        assertEquals(0, BuilderProcess.nextGoalPathFailureCount(false, goal, goal, 1, true));
        assertEquals(1, BuilderProcess.nextGoalPathFailureCount(true, goal, goal, 1, true));
        assertEquals(1, BuilderProcess.nextGoalPathFailureCount(false, goal, goal, 1, false));
    }

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

    @Test
    public void stateComparisonIgnoresWirePowerButKeepsFacing() {
        BlockState unpoweredWire = Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(RedStoneWireBlock.POWER, 0);
        BlockState poweredWire = Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(RedStoneWireBlock.POWER, 15);
        BlockState northFacing = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH);
        BlockState eastFacing = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST);

        assertTrue(BuilderProcess.sameBlockstate(unpoweredWire, poweredWire));
        assertFalse(BuilderProcess.sameBlockstate(northFacing, eastFacing));
    }

    @Test
    public void derivedRedstonePropertiesAreRecognizedAsDynamic() {
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        BlockState lever = Blocks.LEVER.defaultBlockState();
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState();
        BlockState furnace = Blocks.FURNACE.defaultBlockState();

        assertTrue(BuilderProcess.isDerivedStateProperty(wire, property(wire, "power")));
        assertTrue(BuilderProcess.isDerivedStateProperty(wire, property(wire, "north")));
        assertTrue(BuilderProcess.isDerivedStateProperty(lever, property(lever, "powered")));
        assertTrue(BuilderProcess.isDerivedStateProperty(stairs, property(stairs, "shape")));
        assertFalse(BuilderProcess.isDerivedStateProperty(stairs, property(stairs, "facing")));
        assertFalse(BuilderProcess.isDerivedStateProperty(furnace, property(furnace, "lit")));
    }

    private static Property<?> property(BlockState state, String name) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            throw new AssertionError("Expected property " + name + " on " + state.getBlock());
        }
        return property;
    }

    @Test
    public void hitMustMatchExactSupportBlockAndFace() {
        BlockPos support = new BlockPos(1, 2, 3);
        HitResult matching = new BlockHitResult(new Vec3(1.5, 2.5, 3.5), Direction.UP, support, false);
        HitResult wrongFace = new BlockHitResult(new Vec3(1.5, 2.5, 3.5), Direction.NORTH, support, false);
        HitResult wrongBlock = new BlockHitResult(new Vec3(2.5, 2.5, 3.5), Direction.UP, new BlockPos(2, 2, 3), false);

        assertTrue(BuilderProcess.isAimedAtCorrectFace(matching, support, Direction.UP));
        assertFalse(BuilderProcess.isAimedAtCorrectFace(wrongFace, support, Direction.UP));
        assertFalse(BuilderProcess.isAimedAtCorrectFace(wrongBlock, support, Direction.UP));
        assertFalse(BuilderProcess.isAimedAtCorrectFace(null, support, Direction.UP));
    }


}
