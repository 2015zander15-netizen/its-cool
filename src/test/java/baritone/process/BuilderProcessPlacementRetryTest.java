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
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderProcessPlacementRetryTest {

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void pathFailureCountIncreasesOnlyForSameGoalWithoutProgress() {
        GoalBlock goal = new GoalBlock(1, 2, 3);
        GoalBlock differentGoal = new GoalBlock(4, 5, 6);

        assertEquals(1, BuilderProcess.nextGoalPathFailureCount(true, goal, goal, 0, false));
        assertEquals(2, BuilderProcess.nextGoalPathFailureCount(true, goal, goal, 1, false));
        assertEquals(1, BuilderProcess.nextGoalPathFailureCount(true, goal, differentGoal, 1, false));
        assertEquals(0, BuilderProcess.nextGoalPathFailureCount(false, goal, goal, 1, true));
        assertEquals(1, BuilderProcess.nextGoalPathFailureCount(true, goal, goal, 1, true));
        assertEquals(0, BuilderProcess.nextGoalPathFailureCount(false, goal, goal, 1, false));
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
        BlockState hopper = Blocks.HOPPER.defaultBlockState();
        BlockState dispenser = Blocks.DISPENSER.defaultBlockState();

        assertTrue(BuilderProcess.isDerivedStateProperty(wire, property(wire, "power")));
        assertTrue(BuilderProcess.isDerivedStateProperty(wire, property(wire, "north")));
        assertFalse(BuilderProcess.isDerivedStateProperty(lever, property(lever, "powered")));
        assertTrue(BuilderProcess.isDerivedStateProperty(stairs, property(stairs, "shape")));
        assertTrue(BuilderProcess.isDerivedStateProperty(hopper, property(hopper, "enabled")));
        assertTrue(BuilderProcess.isDerivedStateProperty(dispenser, property(dispenser, "triggered")));
        assertFalse(BuilderProcess.isDerivedStateProperty(stairs, property(stairs, "facing")));
        assertFalse(BuilderProcess.isDerivedStateProperty(furnace, property(furnace, "lit")));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, String name, String stringValue) {
        Property<T> property = (Property<T>) state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            throw new AssertionError("Expected property " + name + " on " + state.getBlock());
        }
        T value = property.getValue(stringValue).orElseThrow(
                () -> new AssertionError("Invalid test value " + stringValue + " for " + name)
        );
        return state.setValue(property, value);
    }

    @SuppressWarnings("unchecked")
    private static BlockState withBooleanProperty(BlockState state, String name, boolean value) {
        Property<Boolean> property = (Property<Boolean>) state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            throw new AssertionError("Expected boolean property " + name + " on " + state.getBlock());
        }
        return state.setValue(property, value);
    }

    private static Property<?> property(BlockState state, String name) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            throw new AssertionError("Expected property " + name + " on " + state.getBlock());
        }
        return property;
    }

    @Test
    public void doorAndTrapdoorOpenStateDifferencesUseInteraction() {
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, false);
        BlockState openDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
        BlockState closedTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, false);
        BlockState openTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true);
        BlockState closedGate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, false);
        BlockState openGate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true);

        assertTrue(BuilderProcess.needsManualInteraction(closedDoor, openDoor));
        assertTrue(BuilderProcess.needsManualInteraction(closedTrapdoor, openTrapdoor));
        assertTrue(BuilderProcess.needsManualInteraction(closedGate, openGate));
    }

    @Test
    public void repeaterDelayAndComparatorModeUseInteractions() {
        BlockState repeaterOne = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 1);
        BlockState repeaterTwo = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 2);
        BlockState comparatorCompare = Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE);
        BlockState comparatorSubtract = Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT);

        assertTrue(BuilderProcess.needsManualInteraction(repeaterOne, repeaterTwo));
        assertTrue(BuilderProcess.needsManualInteraction(comparatorCompare, comparatorSubtract));
        assertTrue(BuilderProcess.isDerivedStateProperty(repeaterOne, property(repeaterOne, "powered")));
        assertFalse(BuilderProcess.isDerivedStateProperty(repeaterOne, property(repeaterOne, "delay")));
    }

    @Test
    public void manualTrapdoorStateIsNotIgnoredByIgnoreDirectionSetting() {
        boolean original = Baritone.settings().buildIgnoreDirection.value;
        try {
            Baritone.settings().buildIgnoreDirection.value = true;
            BlockState closed = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, false);
            BlockState open = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true);
            assertFalse(BuilderProcess.sameBlockstate(closed, open));
            assertTrue(BuilderProcess.needsManualInteraction(closed, open));
        } finally {
            Baritone.settings().buildIgnoreDirection.value = original;
        }
    }

    @Test
    public void poweredDoorFlagDoesNotPreventOpenStateInteraction() {
        BlockState current = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, true)
                .setValue(DoorBlock.POWERED, true);
        BlockState desired = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, false)
                .setValue(DoorBlock.POWERED, false);
        BlockState poweredOnlyCurrent = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, false)
                .setValue(DoorBlock.POWERED, true);

        assertTrue(BuilderProcess.needsManualInteraction(current, desired));
        assertFalse(BuilderProcess.needsManualInteraction(poweredOnlyCurrent, desired));
    }

    @Test
    public void noteBlockPitchAndDaylightInversionUseInteractions() {
        BlockState note0 = withProperty(Blocks.NOTE_BLOCK.defaultBlockState(), "note", "0");
        BlockState note5 = withProperty(Blocks.NOTE_BLOCK.defaultBlockState(), "note", "5");
        BlockState daylightNormal = Blocks.DAYLIGHT_DETECTOR.defaultBlockState()
                .setValue(DaylightDetectorBlock.INVERTED, false);
        BlockState daylightInverted = Blocks.DAYLIGHT_DETECTOR.defaultBlockState()
                .setValue(DaylightDetectorBlock.INVERTED, true);

        assertTrue(BuilderProcess.needsManualInteraction(note0, note5));
        assertTrue(BuilderProcess.needsManualInteraction(daylightNormal, daylightInverted));
        assertTrue(BuilderProcess.isDerivedStateProperty(note5, property(note5, "instrument")));
        assertTrue(BuilderProcess.isDerivedStateProperty(note5, property(note5, "powered")));
    }

    @Test
    public void temporaryButtonPulseIsNotTreatedAsAStableToggleTarget() {
        BlockState button = Blocks.STONE_BUTTON.defaultBlockState();
        BlockState unpowered = withBooleanProperty(button, "powered", false);
        BlockState powered = withBooleanProperty(button, "powered", true);

        assertTrue(BuilderProcess.isDerivedStateProperty(
                Blocks.OAK_DOOR.defaultBlockState(),
                property(Blocks.OAK_DOOR.defaultBlockState(), "powered")
        ));
        assertFalse(BuilderProcess.isManualInteractionProperty(button, property(button, "powered")));
        assertFalse(BuilderProcess.needsManualInteraction(unpowered, powered));
    }

    @Test
    public void leverPoweredStateUsesInteractionAndOrientationMismatchDoesNot() {
        BlockState unpoweredLever = Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.POWERED, false);
        BlockState poweredLever = Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true);
        BlockState northDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        BlockState eastDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);

        assertTrue(BuilderProcess.needsManualInteraction(unpoweredLever, poweredLever));
        assertFalse(BuilderProcess.needsManualInteraction(northDoor, eastDoor));
    }

    @Test
    public void interactionRaycastMustHitExactBlock() {
        BlockPos target = new BlockPos(4, 5, 6);
        HitResult matching = new BlockHitResult(new Vec3(4.5, 5.5, 6.5), Direction.NORTH, target, false);
        HitResult wrong = new BlockHitResult(new Vec3(5.5, 5.5, 6.5), Direction.NORTH, new BlockPos(5, 5, 6), false);

        assertTrue(BuilderProcess.isAimedAtBlock(matching, target));
        assertFalse(BuilderProcess.isAimedAtBlock(wrong, target));
        assertFalse(BuilderProcess.isAimedAtBlock(null, target));
    }

    @Test
    public void supportFacePointsFromSupportBackTowardTarget() {
        for (Direction targetToSupport : Direction.values()) {
            assertEquals(targetToSupport.getOpposite(),
                    BuilderProcess.supportFaceForTargetDirection(targetToSupport));
        }
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
