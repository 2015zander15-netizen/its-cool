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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import baritone.api.pathing.goals.GoalBlock;
import baritone.testutil.MinecraftTestBootstrap;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Collections;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuilderProcessPlacementRetryTest {

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        MinecraftTestBootstrap.ensureInitialized();
    }

    @Test
    public void waterBucketSatisfiesBubbleColumnMaterialRequirement() {
        BlockState placeable = BuilderProcess.approximatePlaceableState(
                new ItemStack(Items.WATER_BUCKET), null
        );
        BlockState bubbleColumn = Blocks.BUBBLE_COLUMN.defaultBlockState();

        assertEquals(Blocks.WATER.defaultBlockState(), placeable);
        assertTrue(BuilderProcess.containsBlockState(Collections.singletonList(placeable), bubbleColumn));

        Map<BlockState, Integer> missing = new java.util.HashMap<>();
        BuilderProcess.recordMissingMaterial(missing, bubbleColumn);
        assertEquals(Integer.valueOf(1), missing.get(Blocks.WATER.defaultBlockState()));
        assertFalse(missing.containsKey(bubbleColumn));
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
    public void bubbleColumnIsNormalizedToPlaceableWaterAndSatisfiedByGeneratedColumn() {
        BlockState bubbleColumn = Blocks.BUBBLE_COLUMN.defaultBlockState();
        BlockState waterTarget = BuilderProcess.normalizeSchematicBlockState(bubbleColumn);

        assertEquals(Blocks.WATER.defaultBlockState(), waterTarget);
        assertTrue(BuilderProcess.isBubbleColumnSatisfiedByWater(bubbleColumn, waterTarget));
        assertTrue(BuilderProcess.isBubbleColumnSatisfiedByWater(Blocks.WATER.defaultBlockState(), bubbleColumn));
        assertFalse(BuilderProcess.isBubbleColumnSatisfiedByWater(Blocks.STONE.defaultBlockState(), waterTarget));
    }

    @Test
    public void materialReportCountsPlaceableItemsAndNormalizesBubbleColumnsToWaterBuckets() {
        BlockState[][][] states = new BlockState[1][1][5];
        states[0][0][0] = Blocks.STONE.defaultBlockState();
        states[0][0][1] = Blocks.STONE.defaultBlockState();
        states[0][0][2] = Blocks.BUBBLE_COLUMN.defaultBlockState();
        states[0][0][3] = Blocks.WATER.defaultBlockState();
        states[0][0][4] = Blocks.AIR.defaultBlockState();

        BuilderProcess.MaterialRequirements materials = BuilderProcess.countRequiredMaterials(
                new StaticSchematic(states), Collections.emptyList()
        );

        assertEquals(Integer.valueOf(2), materials.items.get(Items.STONE));
        assertEquals(Integer.valueOf(2), materials.items.get(Items.WATER_BUCKET));
        assertTrue(materials.statesWithoutItems.isEmpty());
    }

    @Test
    public void materialReportShowsInventoryTotalsForMissingCounts() {
        Map<Item, Integer> carried = BuilderProcess.countInventoryItems(java.util.Arrays.asList(
                new ItemStack(Items.STONE, 32),
                new ItemStack(Items.STONE, 5),
                new ItemStack(Items.WATER_BUCKET)
        ));

        assertEquals(Integer.valueOf(37), carried.get(Items.STONE));
        assertEquals(Integer.valueOf(1), carried.get(Items.WATER_BUCKET));
        assertEquals(Integer.valueOf(0), carried.getOrDefault(Items.DIAMOND, 0));
    }

    @Test
    public void filledWaterBucketOnlyCountsForTheWaterCellHitByItsPlacementFace() {
        BlockPos target = new BlockPos(1, 0, 0);
        BlockHitResult hitTowardTarget = new BlockHitResult(
                new Vec3(1, 0.5, 0.5), Direction.EAST, new BlockPos(0, 0, 0), false
        );
        ItemStack waterBucket = new ItemStack(Items.WATER_BUCKET);
        ItemStack emptyBucket = new ItemStack(Items.BUCKET);
        BlockState water = Blocks.WATER.defaultBlockState();

        assertTrue(BuilderProcess.isWaterBucketPlacementCandidate(waterBucket, water, target, hitTowardTarget));
        assertFalse(BuilderProcess.isWaterBucketPlacementCandidate(emptyBucket, water, target, hitTowardTarget));
        assertFalse(BuilderProcess.isWaterBucketPlacementCandidate(
                waterBucket, Blocks.STONE.defaultBlockState(), target, hitTowardTarget
        ));
        assertFalse(BuilderProcess.isWaterBucketPlacementCandidate(
                waterBucket, water, new BlockPos(2, 0, 0), hitTowardTarget
        ));
    }

    @Test
    public void builderPlacementReachIsCappedToVanillaSurvivalRange() {
        assertEquals(4.5D, BuilderProcess.builderInteractionReach(5.0D), 0.0D);
        assertEquals(4.5D, BuilderProcess.builderInteractionReach(4.5D), 0.0D);
        assertEquals(4.25D, BuilderProcess.builderInteractionReach(4.25D), 0.0D);
        assertEquals(0.0D, BuilderProcess.builderInteractionReach(-1.0D), 0.0D);
        assertEquals(0.0D, BuilderProcess.builderInteractionReach(Double.NaN), 0.0D);
    }

    @Test
    public void builderOnlySneaksWhenPlacementCouldActivateTheSupportBlock() {
        assertFalse(BuilderProcess.shouldSneakToPlaceAgainst(Blocks.STONE.defaultBlockState()));
        assertFalse(BuilderProcess.shouldSneakToPlaceAgainst(null));
        assertTrue(BuilderProcess.shouldSneakToPlaceAgainst(Blocks.CHEST.defaultBlockState()));
        assertTrue(BuilderProcess.shouldSneakToPlaceAgainst(Blocks.OAK_BUTTON.defaultBlockState()));
        assertTrue(BuilderProcess.shouldSneakToPlaceAgainst(Blocks.LEVER.defaultBlockState()));
        assertTrue(BuilderProcess.shouldSneakToPlaceAgainst(Blocks.OAK_TRAPDOOR.defaultBlockState()));
        assertTrue(BuilderProcess.shouldSneakToPlaceAgainst(Blocks.CRAFTING_TABLE.defaultBlockState()));
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
    public void redstoneOutputStateIsDerivedButRepeaterDelayIsManual() {
        BlockState repeaterUnpowered = Blocks.REPEATER.defaultBlockState()
                .setValue(RepeaterBlock.POWERED, false);
        BlockState repeaterPowered = repeaterUnpowered.setValue(RepeaterBlock.POWERED, true);
        BlockState delayOne = repeaterUnpowered.setValue(RepeaterBlock.DELAY, 1);
        BlockState delayTwo = repeaterUnpowered.setValue(RepeaterBlock.DELAY, 2);

        assertTrue(BuilderProcess.isDerivedStateProperty(repeaterUnpowered, property(repeaterUnpowered, "powered")));
        assertTrue(BuilderProcess.sameBlockstate(repeaterUnpowered, repeaterPowered, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(delayOne, delayTwo, false, Collections.emptyList()));
    }

    @Test
    public void manualInteractionClickRequiresBothHandsEmptyAndVerifiedAim() {
        assertTrue(BuilderProcess.shouldIssueManualInteractionClick(true, true, true, true));
        assertFalse(BuilderProcess.shouldIssueManualInteractionClick(false, true, true, true));
        assertFalse(BuilderProcess.shouldIssueManualInteractionClick(true, false, true, true));
        assertFalse(BuilderProcess.shouldIssueManualInteractionClick(true, true, false, true));
        assertFalse(BuilderProcess.shouldIssueManualInteractionClick(true, true, true, false));
    }

    @Test
    public void manualToggleDifferencesRemainVisibleToBuilder() {
        BlockState leverOff = Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, false);
        BlockState leverOn = leverOff.setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true);
        BlockState doorClosed = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, false);
        BlockState doorOpen = doorClosed.setValue(DoorBlock.OPEN, true);
        BlockState repeaterOneTick = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 1);
        BlockState repeaterTwoTicks = repeaterOneTick.setValue(RepeaterBlock.DELAY, 2);

        assertTrue(BuilderProcess.needsManualInteraction(leverOff, leverOn, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(doorClosed, doorOpen, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(repeaterOneTick, repeaterTwoTicks, false, Collections.emptyList()));
        assertFalse(BuilderProcess.sameBlockstate(leverOff, leverOn, false, Collections.emptyList()));
    }

    @Test
    public void manualInteractionDetectionCoversSupportedStableToggles() {
        BlockState gateClosed = Blocks.OAK_FENCE_GATE.defaultBlockState();
        BlockState gateOpen = withBooleanProperty(gateClosed, "open", true);
        BlockState trapdoorClosed = Blocks.OAK_TRAPDOOR.defaultBlockState();
        BlockState trapdoorOpen = withBooleanProperty(trapdoorClosed, "open", true);
        BlockState noteLow = withProperty(Blocks.NOTE_BLOCK.defaultBlockState(), "note", "0");
        BlockState noteHigh = withProperty(noteLow, "note", "1");
        BlockState sensorNormal = withBooleanProperty(Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), "inverted", false);
        BlockState sensorInverted = withBooleanProperty(sensorNormal, "inverted", true);
        BlockState comparatorCompare = withProperty(Blocks.COMPARATOR.defaultBlockState(), "mode", "compare");
        BlockState comparatorSubtract = withProperty(comparatorCompare, "mode", "subtract");
        BlockState ironDoorClosed = withBooleanProperty(Blocks.IRON_DOOR.defaultBlockState(), "open", false);
        BlockState ironDoorOpen = withBooleanProperty(ironDoorClosed, "open", true);

        assertTrue(BuilderProcess.needsManualInteraction(gateClosed, gateOpen, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(trapdoorClosed, trapdoorOpen, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(noteLow, noteHigh, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(sensorNormal, sensorInverted, false, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(comparatorCompare, comparatorSubtract, false, Collections.emptyList()));
        assertFalse(BuilderProcess.needsManualInteraction(ironDoorClosed, ironDoorOpen, false, Collections.emptyList()));
    }

    @Test
    public void placementCanBeFollowedByManualStableStateAdjustment() {
        BlockState defaultRepeater = Blocks.REPEATER.defaultBlockState()
                .setValue(RepeaterBlock.DELAY, 1);
        BlockState delayedRepeater = defaultRepeater.setValue(RepeaterBlock.DELAY, 4);
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, false);
        BlockState openDoor = closedDoor.setValue(DoorBlock.OPEN, true);
        BlockState unpoweredLever = Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, false);
        BlockState poweredLever = unpoweredLever.setValue(
                net.minecraft.world.level.block.LeverBlock.POWERED, true
        );

        assertTrue(BuilderProcess.isCompatiblePlacementPrediction(
                defaultRepeater, delayedRepeater, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isCompatiblePlacementPrediction(
                closedDoor, openDoor, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isCompatiblePlacementPrediction(
                unpoweredLever, poweredLever, false, Collections.emptyList()));
    }

    @Test
    public void placementPredictionStillRejectsWrongStructure() {
        BlockState northFacing = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.NORTH);
        BlockState eastFacing = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST);

        assertFalse(BuilderProcess.isCompatiblePlacementPrediction(
                Blocks.STONE.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState(),
                false, Collections.emptyList()));
        assertFalse(BuilderProcess.isCompatiblePlacementPrediction(
                northFacing, eastFacing, false, Collections.emptyList()));
        assertFalse(BuilderProcess.isCompatiblePlacementPrediction(
                null, Blocks.OAK_STAIRS.defaultBlockState(), false, Collections.emptyList()));
    }

    @Test
    public void derivedRedstonePropertiesAreRecognizedAsDynamic() {
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        BlockState lever = Blocks.LEVER.defaultBlockState();
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState();
        BlockState furnace = Blocks.FURNACE.defaultBlockState();
        BlockState hopper = Blocks.HOPPER.defaultBlockState();
        BlockState dispenser = Blocks.DISPENSER.defaultBlockState();
        BlockState repeater = Blocks.REPEATER.defaultBlockState();
        BlockState comparator = Blocks.COMPARATOR.defaultBlockState();

        assertTrue(BuilderProcess.isDerivedStateProperty(wire, property(wire, "power")));
        assertTrue(BuilderProcess.isDerivedStateProperty(wire, property(wire, "north")));
        // Lever POWERED is a stable, manually toggled setting, so the builder must compare it.
        assertFalse(BuilderProcess.isDerivedStateProperty(lever, property(lever, "powered")));
        assertTrue(BuilderProcess.isDerivedStateProperty(stairs, property(stairs, "shape")));
        assertTrue(BuilderProcess.isDerivedStateProperty(hopper, property(hopper, "enabled")));
        assertTrue(BuilderProcess.isDerivedStateProperty(dispenser, property(dispenser, "triggered")));
        assertTrue(BuilderProcess.isDerivedStateProperty(repeater, property(repeater, "powered")));
        assertTrue(BuilderProcess.isDerivedStateProperty(comparator, property(comparator, "powered")));
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        assertTrue(BuilderProcess.isDerivedStateProperty(door, property(door, "powered")));
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
    public void manualInteractionsRequireFinalPhaseAndNoStructuralWork() {
        assertFalse(BuilderProcess.manualInteractionsAllowed(false, true));
        assertFalse(BuilderProcess.manualInteractionsAllowed(false, false));
        assertFalse(BuilderProcess.manualInteractionsAllowed(true, false));
        assertTrue(BuilderProcess.manualInteractionsAllowed(true, true));
    }

    @Test
    public void deferredInteractionClassificationSeparatesTogglesFromStructuralWork() {
        BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, false);
        BlockState openDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
        BlockState wrongFacingDoor = closedDoor.setValue(DoorBlock.FACING, Direction.EAST);
        BlockState poweredOpenDoor = openDoor.setValue(DoorBlock.POWERED, true);
        BlockState desiredClosedDoor = closedDoor.setValue(DoorBlock.POWERED, false);

        assertTrue(BuilderProcess.isDeferredManualStateMismatch(
                closedDoor, openDoor, false, Collections.emptyList(), false));
        assertFalse(BuilderProcess.isDeferredManualStateMismatch(
                wrongFacingDoor, closedDoor, false, Collections.emptyList(), false));
        assertTrue(BuilderProcess.isDeferredManualStateMismatch(
                poweredOpenDoor, desiredClosedDoor, false, Collections.emptyList(), false));
        assertFalse(BuilderProcess.isDeferredManualStateMismatch(
                Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                false, Collections.emptyList(), false));
        assertFalse(BuilderProcess.isDeferredManualStateMismatch(
                closedDoor, closedDoor, false, Collections.emptyList(), true));
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
    public void openableOpenStateIsNotIgnoredWithIgnoreDirectionEnabled() {
        BlockState ironTrapdoorClosed = Blocks.IRON_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, false);
        BlockState ironTrapdoorOpen = Blocks.IRON_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true);
        BlockState woodenTrapdoorClosed = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, false);
        BlockState woodenTrapdoorOpen = Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true);

        assertTrue(BuilderProcess.isOpenableOpenProperty(ironTrapdoorClosed,
                property(ironTrapdoorClosed, "open")));
        assertFalse(BuilderProcess.sameBlockstate(
                ironTrapdoorClosed, ironTrapdoorOpen, true, Collections.emptyList()));
        assertTrue(BuilderProcess.isExternallyPoweredOpenableMismatch(
                ironTrapdoorClosed, ironTrapdoorOpen, true, Collections.emptyList()));
        assertFalse(BuilderProcess.sameBlockstate(
                woodenTrapdoorClosed, woodenTrapdoorOpen, true, Collections.emptyList()));
        assertTrue(BuilderProcess.needsManualInteraction(
                woodenTrapdoorClosed, woodenTrapdoorOpen, true, Collections.emptyList()));
    }

    @Test
    public void ironDoorAndIronTrapdoorOpenStatesAreNotHandToggles() {
        BlockState ironDoorClosed = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, false);
        BlockState ironDoorOpen = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, true);
        BlockState ironTrapdoorClosed = Blocks.IRON_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, false);
        BlockState ironTrapdoorOpen = Blocks.IRON_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.OPEN, true);

        assertFalse(BuilderProcess.supportsManualOpenToggle(ironDoorClosed));
        assertFalse(BuilderProcess.supportsManualOpenToggle(ironTrapdoorClosed));
        assertFalse(BuilderProcess.needsManualInteraction(
                ironDoorClosed, ironDoorOpen, false, Collections.emptyList()));
        assertFalse(BuilderProcess.needsManualInteraction(
                ironTrapdoorClosed, ironTrapdoorOpen, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isExternallyPoweredOpenableMismatch(
                ironDoorClosed, ironDoorOpen, false, Collections.emptyList()));
        assertTrue(BuilderProcess.isExternallyPoweredOpenableMismatch(
                ironTrapdoorClosed, ironTrapdoorOpen, false, Collections.emptyList()));
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
        assertTrue(BuilderProcess.isDerivedStateProperty(sensor, property(sensor, "sculk_sensor_phase")));
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
    public void playerBoundsOnlyBlockOverlappingPlacementTargets() {
        AABB player = new AABB(0.2, 64.0, 0.2, 0.8, 65.8, 0.8);
        BlockPos insideBody = new BlockPos(0, 65, 0);
        BlockPos adjacentHeadHeightWall = new BlockPos(1, 65, 0);
        BlockPos nextToBody = new BlockPos(-1, 64, 0);

        assertTrue(BuilderProcess.targetIntersectsPlayerBounds(insideBody, player));
        assertFalse(BuilderProcess.targetIntersectsPlayerBounds(adjacentHeadHeightWall, player));
        assertFalse(BuilderProcess.targetIntersectsPlayerBounds(nextToBody, player));
    }

    @Test
    public void failedPlacementApproachIsExcludedButAnotherNeighborRemainsReachable() {
        BlockPos target = new BlockPos(5, 64, 5);
        BlockPos failedFeet = new BlockPos(4, 64, 5);
        BlockPos alternateFeet = new BlockPos(6, 64, 5);
        BuilderProcess.GoalPlacementApproach goal = new BuilderProcess.GoalPlacementApproach(
                target, Collections.singleton(failedFeet), true
        );

        assertFalse(goal.isInGoal(failedFeet.getX(), failedFeet.getY(), failedFeet.getZ()));
        assertTrue(goal.isInGoal(alternateFeet.getX(), alternateFeet.getY(), alternateFeet.getZ()));
        // A lower, horizontally adjacent stance is permitted when same-level approaches are enabled.
        assertTrue(goal.isInGoal(alternateFeet.getX(), target.getY() - 1, alternateFeet.getZ()));
        // The target cell and feet cell directly below it overlap the player's body.
        assertFalse(goal.isInGoal(target.getX(), target.getY(), target.getZ()));
        assertFalse(goal.isInGoal(target.getX(), target.getY() - 1, target.getZ()));

        BuilderProcess.GoalPlacementApproach noSameLevel = new BuilderProcess.GoalPlacementApproach(
                target, Collections.emptySet(), false
        );
        assertFalse(noSameLevel.isInGoal(target.getX(), target.getY() - 1, target.getZ()));
        assertFalse(noSameLevel.isInGoal(alternateFeet.getX(), target.getY() - 1, alternateFeet.getZ()));
    }

    @Test
    public void approachGoalIdentityChangesWhenFailedPositionsChange() {
        BlockPos target = new BlockPos(5, 64, 5);
        BuilderProcess.GoalPlacementApproach first = new BuilderProcess.GoalPlacementApproach(
                target, Collections.singleton(new BlockPos(4, 64, 5)), true
        );
        BuilderProcess.GoalPlacementApproach second = new BuilderProcess.GoalPlacementApproach(
                target, Collections.singleton(new BlockPos(6, 64, 5)), true
        );

        assertFalse(first.equals(second));
        assertFalse(first.hashCode() == second.hashCode());
    }

    @Test
    public void onlyNearbyFeetPositionsAreRecordedAsPlacementApproaches() {
        BlockPos target = new BlockPos(5, 64, 5);
        assertFalse(BuilderProcess.isPotentialPlacementApproach(target, target));
        assertTrue(BuilderProcess.isPotentialPlacementApproach(target, new BlockPos(6, 64, 5)));
        assertTrue(BuilderProcess.isPotentialPlacementApproach(target, new BlockPos(5, 65, 5)));
        assertFalse(BuilderProcess.isPotentialPlacementApproach(target, new BlockPos(5, 62, 5)));
        assertFalse(BuilderProcess.isPotentialPlacementApproach(target, new BlockPos(8, 64, 5)));
    }

    @Test
    public void placementApproachSupportsAllSixAdjacentSides() {
        Direction[] directions = BuilderProcess.placementSupportDirections();

        assertEquals(6, directions.length);
        for (Direction direction : Direction.values()) {
            assertTrue(java.util.Arrays.asList(directions).contains(direction));
        }
        assertTrue(java.util.Arrays.asList(directions).contains(Direction.UP));
    }

    @Test
    public void supportFacePointsFromSupportBackTowardTarget() {
        for (Direction targetToSupport : Direction.values()) {
            assertEquals(targetToSupport.getOpposite(),
                    BuilderProcess.supportFaceForTargetDirection(targetToSupport));
        }
    }

    @Test
    public void failedLayerSkippingHonorsManualPhaseAndStopHeight() {
        assertTrue(BuilderProcess.shouldSkipFailedStructuralLayer(
                false, true, true, 0, 4, 100, 10));
        assertFalse(BuilderProcess.shouldSkipFailedStructuralLayer(
                true, true, true, 0, 4, 100, 10));
        assertFalse(BuilderProcess.shouldSkipFailedStructuralLayer(
                false, false, true, 0, 4, 100, 10));
        assertFalse(BuilderProcess.shouldSkipFailedStructuralLayer(
                false, true, false, 0, 4, 100, 10));
        assertTrue(BuilderProcess.shouldSkipFailedStructuralLayer(
                false, true, true, 0, 4, 5, 10));
        assertFalse(BuilderProcess.shouldSkipFailedStructuralLayer(
                false, true, true, 2, 4, 5, 10));
        assertFalse(BuilderProcess.shouldSkipFailedStructuralLayer(
                false, true, true, 0, 4, 0, 10));
    }

    @Test
    public void layerAdvanceStopsAtSchematicDepthEvenWhenStopHeightIsLarger() {
        assertTrue(BuilderProcess.hasMoreStructuralLayers(0, 4, 100, 10));
        assertTrue(BuilderProcess.hasMoreStructuralLayers(1, 4, 100, 10));
        assertFalse(BuilderProcess.hasMoreStructuralLayers(3, 4, 100, 10));
        assertTrue(BuilderProcess.hasMoreStructuralLayers(1, 4, 5, 10));
        assertFalse(BuilderProcess.hasMoreStructuralLayers(2, 4, 5, 10));
        assertFalse(BuilderProcess.hasMoreStructuralLayers(0, 4, 0, 10));
    }

    @Test
    public void structuralLayerBoundsRespectStopHeightInBothOrders() {
        // Bottom-up: only y=0..4 is built when stopAtHeight is 5, even if the layer ends at y=7.
        assertEquals(0, BuilderProcess.structuralLayerMinYInclusive(10, 2, 4, false, 5));
        assertEquals(4, BuilderProcess.structuralLayerMaxYInclusive(10, 2, 4, false, 5));
        // Top-down: depth 4 includes y=6..9, never descending into lower unbuilt layers.
        assertEquals(6, BuilderProcess.structuralLayerMinYInclusive(10, 1, 4, true, 4));
        assertEquals(9, BuilderProcess.structuralLayerMaxYInclusive(10, 1, 4, true, 4));
        // Clamp a large layer index to schematic height, and allow a zero stop boundary to build nothing.
        assertEquals(10, BuilderProcess.completedLayerDepth(100, 4, 50, 10));
        assertEquals(-1, BuilderProcess.structuralLayerMaxYInclusive(10, 1, 4, false, 0));
        assertEquals(10, BuilderProcess.structuralLayerMinYInclusive(10, 1, 4, true, 0));
    }

    @Test
    public void finalManualInteractionPassCoversBuiltLayersButNotUnbuiltLayers() {
        assertEquals(0, BuilderProcess.manualPhaseMinYInclusive(10, 0, true, false, 5));
        assertEquals(4, BuilderProcess.manualPhaseMaxYInclusive(7, true, false, 5));
        assertEquals(6, BuilderProcess.manualPhaseMinYInclusive(10, 6, true, true, 4));
        assertEquals(9, BuilderProcess.manualPhaseMaxYInclusive(9, true, true, 4));

        // Bottom-up builds clamp to stopAtHeight even if the completed last layer overshoots it.
        assertTrue(BuilderProcess.isYIncludedInCurrentPass(
                4, 0, 7, true,
                BuilderProcess.manualPhaseMinYInclusive(10, 0, true, false, 5),
                BuilderProcess.manualPhaseMaxYInclusive(7, true, false, 5)));
        assertFalse(BuilderProcess.isYIncludedInCurrentPass(
                5, 0, 7, true,
                BuilderProcess.manualPhaseMinYInclusive(10, 0, true, false, 5),
                BuilderProcess.manualPhaseMaxYInclusive(7, true, false, 5)));

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
