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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Collections;
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

        assertTrue(BuilderProcess.sameBlockstate(unpoweredWire, poweredWire, false, Collections.emptyList()));
        assertFalse(BuilderProcess.sameBlockstate(northFacing, eastFacing, false, Collections.emptyList()));
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
    public void chestTypeAndVineConnectionsAreNeighborDerived() {
        BlockState singleChest = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.TYPE, ChestType.SINGLE);
        BlockState leftChest = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockState northFacingChest = singleChest.setValue(ChestBlock.FACING, Direction.NORTH);
        BlockState eastFacingChest = leftChest.setValue(ChestBlock.FACING, Direction.EAST);
        BlockState vine = Blocks.VINE.defaultBlockState();
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        BlockState chorusPlant = Blocks.CHORUS_PLANT.defaultBlockState();

        assertTrue(BuilderProcess.isDerivedStateProperty(singleChest, property(singleChest, "type")));
        assertTrue(BuilderProcess.sameBlockstate(singleChest, leftChest, false, Collections.emptyList()));
        assertFalse(BuilderProcess.sameBlockstate(northFacingChest, eastFacingChest, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isDerivedStateProperty(vine, property(vine, "north")));
        assertTrue(BuilderProcess.isDerivedStateProperty(gate, property(gate, "in_wall")));
        assertTrue(BuilderProcess.isDerivedStateProperty(chorusPlant, property(chorusPlant, "down")));
    }

    @Test
    public void deferredInteractionClassificationSeparatesTogglesFromStructuralWork() {
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, false);
        BlockState openDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
        BlockState wrongFacingDoor = closedDoor.setValue(DoorBlock.FACING, Direction.EAST);
        BlockState poweredOpenDoor = openDoor.setValue(DoorBlock.POWERED, true);
        BlockState desiredClosedDoor = closedDoor.setValue(DoorBlock.POWERED, false);

        assertTrue(BuilderProcess.isDeferredManualStateMismatch(
                closedDoor, openDoor, false, Collections.emptyList()));
        assertFalse(BuilderProcess.isDeferredManualStateMismatch(
                wrongFacingDoor, closedDoor, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isDeferredManualStateMismatch(
                poweredOpenDoor, desiredClosedDoor, false, Collections.emptyList()));
        assertFalse(BuilderProcess.isDeferredManualStateMismatch(
                Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                false, Collections.emptyList()));
    }

    @Test
    public void structuralGateDefersOrdinaryBlocksButAllowsStatefulInteractions() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, false);
        BlockState openDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
        BlockState poweredOpenDoor = openDoor.setValue(DoorBlock.POWERED, true);
        BlockState desiredClosedDoor = closedDoor.setValue(DoorBlock.POWERED, false);
        BlockState wrongFacingDoor = closedDoor.setValue(DoorBlock.FACING, Direction.EAST);
        BlockState unpoweredWire = Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(RedStoneWireBlock.POWER, 0);
        BlockState poweredWire = Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(RedStoneWireBlock.POWER, 15);

        assertTrue(BuilderProcess.isStructuralWorkMismatch(air, stone, false, Collections.emptyList()));
        assertFalse(BuilderProcess.isStructuralWorkMismatch(closedDoor, openDoor, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isStructuralWorkMismatch(wrongFacingDoor, closedDoor, false, Collections.emptyList()));
        assertFalse(BuilderProcess.isStructuralWorkMismatch(wrongFacingDoor, closedDoor, true, Collections.emptyList()));
        assertFalse(BuilderProcess.isStructuralWorkMismatch(
                poweredOpenDoor, desiredClosedDoor, false, Collections.emptyList()));
        assertFalse(BuilderProcess.isStructuralWorkMismatch(
                unpoweredWire, poweredWire, false, Collections.emptyList()));
    }

    @Test
    public void doorAndTrapdoorOpenStateDifferencesUseInteraction() {
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, false);
        BlockState openDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
        BlockState closedTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, false);
        BlockState openTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true);
        BlockState closedGate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, false);
        BlockState openGate = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true);

        assertTrue(BuilderProcess.needsManualInteraction(closedDoor, openDoor, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(closedTrapdoor, openTrapdoor, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(closedGate, openGate, false, Collections.emptyList()));
    }

    @Test
    public void repeaterDelayAndComparatorModeUseInteractions() {
        BlockState repeaterOne = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 1);
        BlockState repeaterTwo = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 2);
        BlockState comparatorCompare = Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE);
        BlockState comparatorSubtract = Blocks.COMPARATOR.defaultBlockState()
                .setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT);

        assertTrue(BuilderProcess.needsManualInteraction(repeaterOne, repeaterTwo, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(comparatorCompare, comparatorSubtract, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isDerivedStateProperty(repeaterOne, property(repeaterOne, "powered")));
        assertFalse(BuilderProcess.isDerivedStateProperty(repeaterOne, property(repeaterOne, "delay")));
    }

    @Test
    public void repeaterLockStateIsDerivedButDelayRemainsStructural() {
        BlockState unlockedRepeater = Blocks.REPEATER.defaultBlockState()
                .setValue(RepeaterBlock.LOCKED, false)
                .setValue(RepeaterBlock.DELAY, 1);
        BlockState lockedRepeater = unlockedRepeater.setValue(RepeaterBlock.LOCKED, true);
        BlockState differentDelay = unlockedRepeater.setValue(RepeaterBlock.DELAY, 2);

        assertTrue(BuilderProcess.isDerivedStateProperty(lockedRepeater, property(lockedRepeater, "locked")));
        assertTrue(BuilderProcess.sameBlockstate(unlockedRepeater, lockedRepeater, false, Collections.emptyList()));
        assertFalse(BuilderProcess.sameBlockstate(unlockedRepeater, differentDelay, false, Collections.emptyList()));
    }

    @Test
    public void sculkSensorPhaseIsTreatedAsRuntimeDerivedState() {
        BlockState sensor = Blocks.SCULK_SENSOR.defaultBlockState();
        assertTrue(BuilderProcess.isDerivedStateProperty(sensor, property(sensor, "phase")));
    }

    @Test
    public void tripwireAttachmentIsNeighborDerived() {
        BlockState detached = Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.ATTACHED, false);
        BlockState attached = detached.setValue(TripWireBlock.ATTACHED, true);

        assertTrue(BuilderProcess.isDerivedStateProperty(attached, property(attached, "attached")));
        assertTrue(BuilderProcess.sameBlockstate(detached, attached, false, Collections.emptyList()));
    }

    @Test
    public void manualTrapdoorStateIsNotIgnoredByIgnoreDirectionSetting() {
        BlockState closed = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, false);
        BlockState open = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true);
        assertFalse(BuilderProcess.sameBlockstate(closed, open, true, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(closed, open, true, Collections.emptyList()));
    }

    @Test
    public void toggleRetryHistoryResetsOnExactRedstoneStateChange() {
        BlockState poweredOpen = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, true)
                .setValue(DoorBlock.POWERED, true);
        BlockState unpoweredOpen = poweredOpen.setValue(DoorBlock.POWERED, false);
        BlockState desiredClosed = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, false)
                .setValue(DoorBlock.POWERED, false);

        assertTrue(BuilderProcess.toggleAttemptStateUnchanged(
                poweredOpen, poweredOpen, desiredClosed, desiredClosed
        ));
        assertFalse(BuilderProcess.toggleAttemptStateUnchanged(
                poweredOpen, unpoweredOpen, desiredClosed, desiredClosed
        ));
        assertFalse(BuilderProcess.toggleAttemptStateUnchanged(
                poweredOpen, poweredOpen, desiredClosed, poweredOpen
        ));
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
        BlockState unpoweredClosedDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, false)
                .setValue(DoorBlock.POWERED, false);
        BlockState expectedPoweredOpenDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, true)
                .setValue(DoorBlock.POWERED, true);
        BlockState manuallyOpenDoor = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, true)
                .setValue(DoorBlock.POWERED, false);
        BlockState poweredOpenTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true)
                .setValue(TrapDoorBlock.POWERED, true);
        BlockState closedTrapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, false)
                .setValue(TrapDoorBlock.POWERED, false);
        BlockState poweredOpenGate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.OPEN, true)
                .setValue(FenceGateBlock.POWERED, true);
        BlockState closedGate = Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.OPEN, false)
                .setValue(FenceGateBlock.POWERED, false);

        assertFalse(BuilderProcess.needsManualInteraction(current, desired, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(manuallyOpenDoor, desired, false, Collections.emptyList()));
        assertFalse(BuilderProcess.needsManualInteraction(
                unpoweredClosedDoor, expectedPoweredOpenDoor, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isExternallyPoweredOpenableMismatch(
                unpoweredClosedDoor, expectedPoweredOpenDoor, false, Collections.emptyList()));
        assertFalse(BuilderProcess.needsManualInteraction(poweredOnlyCurrent, desired, false, Collections.emptyList()));
        assertFalse(BuilderProcess.sameBlockstate(current, desired, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isExternallyPoweredOpenableMismatch(current, desired, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isExternallyPoweredOpenableMismatch(poweredOpenTrapdoor, closedTrapdoor, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isExternallyPoweredOpenableMismatch(poweredOpenGate, closedGate, false, Collections.emptyList()));
        BlockState wrongFacingPoweredDoor = current.setValue(DoorBlock.FACING, Direction.EAST);
        assertFalse(BuilderProcess.isExternallyPoweredOpenableMismatch(
                wrongFacingPoweredDoor, desired, false, Collections.emptyList()
        ));
        assertTrue(BuilderProcess.isExternallyPoweredOpenableMismatch(
                wrongFacingPoweredDoor, desired, true, Collections.emptyList()
        ));
        assertFalse(BuilderProcess.isDerivedStateProperty(current, property(current, "open")));
    }

    @Test
    public void noteBlockPitchAndDaylightInversionUseInteractions() {
        BlockState note0 = withProperty(Blocks.NOTE_BLOCK.defaultBlockState(), "note", "0");
        BlockState note5 = withProperty(Blocks.NOTE_BLOCK.defaultBlockState(), "note", "5");
        BlockState daylightNormal = Blocks.DAYLIGHT_DETECTOR.defaultBlockState()
                .setValue(DaylightDetectorBlock.INVERTED, false);
        BlockState daylightInverted = Blocks.DAYLIGHT_DETECTOR.defaultBlockState()
                .setValue(DaylightDetectorBlock.INVERTED, true);

        assertTrue(BuilderProcess.needsManualInteraction(note0, note5, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(daylightNormal, daylightInverted, false, Collections.emptyList()));
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
        assertFalse(BuilderProcess.needsManualInteraction(unpowered, powered, false, Collections.emptyList()));
    }

    @Test
    public void leverPoweredStateUsesInteractionAndOrientationMismatchDoesNot() {
        BlockState unpoweredLever = Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.POWERED, false);
        BlockState poweredLever = Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true);
        BlockState northDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        BlockState eastDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);

        assertTrue(BuilderProcess.needsManualInteraction(unpoweredLever, poweredLever, false, Collections.emptyList()));
        assertFalse(BuilderProcess.needsManualInteraction(northDoor, eastDoor, true, Collections.emptyList()));
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
    public void finalManualInteractionPassCoversBuiltLayersButNotUnbuiltLayers() {
        assertTrue(BuilderProcess.isYIncludedInCurrentPass(2, 0, 3, false, 0, 3));
        assertFalse(BuilderProcess.isYIncludedInCurrentPass(5, 0, 3, false, 0, 3));

        // A final bottom-up pass covers all built layers, but honors a stop-height boundary.
        assertTrue(BuilderProcess.isYIncludedInCurrentPass(2, 6, 8, true, 0, 8));
        assertTrue(BuilderProcess.isYIncludedInCurrentPass(8, 6, 8, true, 0, 8));
        assertFalse(BuilderProcess.isYIncludedInCurrentPass(9, 6, 8, true, 0, 8));

        // A final top-down pass includes its completed upper range, not unbuilt lower layers.
        assertTrue(BuilderProcess.isYIncludedInCurrentPass(7, 6, 8, true, 4, 8));
        assertFalse(BuilderProcess.isYIncludedInCurrentPass(3, 6, 8, true, 4, 8));
    }

    @Test
    public void aabbFaceMultipliersSampleTheRequestedFace() {
        AABB box = new AABB(2, 4, 6, 10, 12, 14);

        for (net.minecraft.world.phys.Vec3 point : BuilderProcess.aabbSideMultipliers(Direction.UP)) {
            assertEquals(box.maxY, BuilderProcess.sampleCoordinate(box.minY, box.maxY, point.y), 0.0001);
        }
        for (net.minecraft.world.phys.Vec3 point : BuilderProcess.aabbSideMultipliers(Direction.DOWN)) {
            assertEquals(box.minY, BuilderProcess.sampleCoordinate(box.minY, box.maxY, point.y), 0.0001);
        }
        for (net.minecraft.world.phys.Vec3 point : BuilderProcess.aabbSideMultipliers(Direction.EAST)) {
            assertEquals(box.maxX, BuilderProcess.sampleCoordinate(box.minX, box.maxX, point.x), 0.0001);
        }
        for (net.minecraft.world.phys.Vec3 point : BuilderProcess.aabbSideMultipliers(Direction.WEST)) {
            assertEquals(box.minX, BuilderProcess.sampleCoordinate(box.minX, box.maxX, point.x), 0.0001);
        }
        for (net.minecraft.world.phys.Vec3 point : BuilderProcess.aabbSideMultipliers(Direction.SOUTH)) {
            assertEquals(box.maxZ, BuilderProcess.sampleCoordinate(box.minZ, box.maxZ, point.z), 0.0001);
        }
        for (net.minecraft.world.phys.Vec3 point : BuilderProcess.aabbSideMultipliers(Direction.NORTH)) {
            assertEquals(box.minZ, BuilderProcess.sampleCoordinate(box.minZ, box.maxZ, point.z), 0.0001);
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

        assertTrue(BuilderProcess.isAimedAtBlock(matching, support));
        assertTrue(BuilderProcess.isAimedAtBlock(wrongFace, support));
        assertFalse(BuilderProcess.isAimedAtBlock(wrongBlock, support));
        assertFalse(BuilderProcess.isAimedAtBlock(null, support));
    }


}
