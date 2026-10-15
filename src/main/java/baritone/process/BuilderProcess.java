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

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.schematic.SubstituteSchematic;
import baritone.api.schematic.RotatedSchematic;
import baritone.api.schematic.MirroredSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.PathingCommandContext;
import baritone.utils.schematic.MapArtSchematic;
import baritone.utils.schematic.SelectionSchematic;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.format.defaults.LitematicaSchematic;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private static final int MAX_PLACEMENT_ATTEMPTS = 5;
    private static final int MAX_PLACEMENT_APPROACH_FAILURES = 4_096;
    private static final int INITIAL_PLACEMENT_RETRY_TICKS = 4;
    private static final int MAX_PLACEMENT_RETRY_TICKS = 32;

    private static final Set<Property<?>> ORIENTATION_PROPS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
                    StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
                    TrapDoorBlock.OPEN, TrapDoorBlock.HALF
            )));

    private static final Set<String> DYNAMIC_STATE_PROPERTIES =
            ImmutableSet.of("power", "powered", "lit", "extended", "triggered", "enabled", "locked",
                    "attached", "phase", "sculk_sensor_phase");
    private static final Set<String> NEIGHBOR_CONNECTION_PROPERTIES =
            ImmutableSet.of("north", "east", "south", "west", "up", "down");

    private HashSet<BetterBlockPos> incorrectPositions;
    private LongOpenHashSet observedCompleted; // positions that are completed even if they're out of render distance and we can't make sure right now
    private final LongOpenHashSet deferredManualInteractionPositions = new LongOpenHashSet();
    private String name;
    private ISchematic realSchematic;
    private ISchematic schematic;
    private Vec3i origin;
    private int ticks;
    private boolean paused;
    private int layer;
    private int numRepeats;
    private List<BlockState> approxPlaceable;
    private int builderTick;
    private boolean manualInteractionPhase;
    private int currentLayerMinYInclusive;
    private int currentLayerMaxYInclusive;
    private int manualPhaseMinY;
    private int manualPhaseMaxY = -1;
    private Goal lastRequestedGoal;
    private int consecutivePathFailures;
    private BlockPos lastFailureObservedPlayerPos;
    private int lastFailureIncorrectPositionsSize;
    private final Map<BlockPos, PlacementAttempt> placementAttempts = new HashMap<>();
    private final Map<BlockPos, PlacementApproachFailure> placementApproachFailures = new HashMap<>();
    private final Map<BlockPos, ToggleAttempt> toggleAttempts = new HashMap<>();
    public int stopAtHeight = 0;

    private static final class PlacementAttempt {
        private final BlockState originalState;
        private final BlockState desiredState;
        private final BlockPos supportPos;
        private final BlockState originalSupportState;
        private int attempts;
        private int retryAtTick;

        private PlacementAttempt(BlockState originalState, BlockState desiredState,
                BlockPos supportPos, BlockState originalSupportState) {
            this.originalState = originalState;
            this.desiredState = desiredState;
            this.supportPos = new BlockPos(supportPos);
            this.originalSupportState = originalSupportState;
        }
    }

    private static final class PlacementApproachFailure {
        private final BlockState targetState;
        private final BlockState desiredState;
        private final Map<BlockPos, BlockState> surroundingStates;
        private final Set<BlockPos> failedFeetPositions = new HashSet<>();

        private PlacementApproachFailure(BlockState targetState, BlockState desiredState,
                Map<BlockPos, BlockState> surroundingStates) {
            this.targetState = targetState;
            this.desiredState = desiredState;
            this.surroundingStates = new HashMap<>(surroundingStates);
        }
    }

    private static final class ToggleAttempt {
        private final BlockState originalState;
        private final BlockState desiredState;
        private int attempts;
        private int retryAtTick;

        private ToggleAttempt(BlockState originalState, BlockState desiredState) {
            this.originalState = originalState;
            this.desiredState = desiredState;
        }
    }

    static int nextGoalPathFailureCount(boolean calcFailed, Goal previousGoal, Goal currentGoal,
            int previousCount, boolean progressChanged) {
        if (progressChanged || previousGoal == null || !previousGoal.equals(currentGoal)) {
            return calcFailed ? 1 : 0;
        }
        // calcFailed is a one-tick event flag; preserve the count across ordinary ticks
        // until the same goal makes real progress or a different goal is requested.
        return calcFailed ? previousCount + 1 : previousCount;
    }

    static boolean isYIncludedInCurrentPass(int y, int layerMinY, int layerMaxY,
            boolean manualInteractionPhase, int manualMinY, int manualMaxY) {
        int minY = manualInteractionPhase ? manualMinY : layerMinY;
        int maxY = manualInteractionPhase ? manualMaxY : layerMaxY;
        return y >= minY && y <= maxY;
    }

    static boolean manualInteractionsAllowed(boolean manualInteractionPhase, boolean noStructuralWork) {
        return manualInteractionPhase && noStructuralWork;
    }

    static boolean isDeferredManualStateMismatch(BlockState current, BlockState desired,
            boolean ignoreDirection, List<String> ignoredProperties) {
        return isDeferredManualStateMismatch(
                current, desired, ignoreDirection, ignoredProperties, valid(current, desired, false)
        );
    }

    /**
     * Pure classification step separated from runtime build settings so it can be tested without
     * creating a live Minecraft client. alreadyValid is supplied by the active build policy.
     */
    static boolean isDeferredManualStateMismatch(BlockState current, BlockState desired,
            boolean ignoreDirection, List<String> ignoredProperties, boolean alreadyValid) {
        return !alreadyValid
                && !isStructuralWorkMismatch(current, desired, ignoreDirection, ignoredProperties);
    }

    static int completedLayerDepth(int layer, int layerHeight, int stopAtHeight, int schematicHeight) {
        if (layer <= 0 || layerHeight <= 0 || stopAtHeight <= 0 || schematicHeight <= 0) {
            return 0;
        }
        long layerDepth = (long) layer * layerHeight;
        return (int) Math.min(Math.min(layerDepth, (long) stopAtHeight), (long) schematicHeight);
    }

    static boolean hasMoreStructuralLayers(int layer, int layerHeight, int stopAtHeight, int schematicHeight) {
        int targetDepth = Math.max(0, Math.min(stopAtHeight, schematicHeight));
        return completedLayerDepth(layer, layerHeight, stopAtHeight, schematicHeight) < targetDepth;
    }

    static boolean shouldSkipFailedStructuralLayer(boolean manualInteractionPhase,
            boolean skipFailedLayers, boolean buildInLayers,
            int layer, int layerHeight, int stopAtHeight, int schematicHeight) {
        return !manualInteractionPhase
                && skipFailedLayers
                && buildInLayers
                && hasMoreStructuralLayers(layer, layerHeight, stopAtHeight, schematicHeight);
    }

    static int structuralLayerMinYInclusive(int schematicHeight, int layer, int layerHeight,
            boolean topDown, int stopAtHeight) {
        return topDown
                ? schematicHeight - completedLayerDepth(layer, layerHeight, stopAtHeight, schematicHeight)
                : 0;
    }

    static int structuralLayerMaxYInclusive(int schematicHeight, int layer, int layerHeight,
            boolean topDown, int stopAtHeight) {
        return topDown
                ? schematicHeight - 1
                : completedLayerDepth(layer, layerHeight, stopAtHeight, schematicHeight) - 1;
    }

    static int manualPhaseMinYInclusive(int schematicHeight, int currentLayerMinY,
            boolean layered, boolean topDown, int stopAtHeight) {
        if (!layered || !topDown) {
            return currentLayerMinY;
        }
        int builtDepth = Math.max(0, Math.min(stopAtHeight, schematicHeight));
        return Math.max(currentLayerMinY, schematicHeight - builtDepth);
    }

    static int manualPhaseMaxYInclusive(int currentLayerMaxY,
            boolean layered, boolean topDown, int stopAtHeight) {
        if (!layered || topDown) {
            return currentLayerMaxY;
        }
        if (stopAtHeight <= 0) {
            return -1;
        }
        return Math.min(currentLayerMaxY, stopAtHeight - 1);
    }

    static int placementRetryDelayTicks(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be at least 1");
        }
        int exponent = Math.min(attemptNumber - 1, 3);
        return Math.min(INITIAL_PLACEMENT_RETRY_TICKS << exponent, MAX_PLACEMENT_RETRY_TICKS);
    }

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    private void reportUnsupportedLitematicaPayload(ISchematic schematic) {
        if (schematic instanceof LitematicaSchematic) {
            String warning = ((LitematicaSchematic) schematic).getUnsupportedPayloadWarning();
            if (warning != null) {
                logDirect(warning);
            }
        }
    }

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        reportUnsupportedLitematicaPayload(schematic);
        placementAttempts.clear();
        placementApproachFailures.clear();
        toggleAttempts.clear();
        deferredManualInteractionPositions.clear();
        observedCompleted = new LongOpenHashSet();
        incorrectPositions = null;
        builderTick = 0;
        manualInteractionPhase = false;
        lastRequestedGoal = null;
        consecutivePathFailures = 0;
        lastFailureObservedPlayerPos = null;
        lastFailureIncorrectPositionsSize = 0;
        this.name = name;
        this.schematic = schematic;
        this.realSchematic = null;
        boolean buildingSelectionSchematic = schematic instanceof SelectionSchematic;
        if (!Baritone.settings().buildSubstitutes.value.isEmpty()) {
            this.schematic = new SubstituteSchematic(this.schematic, Baritone.settings().buildSubstitutes.value);
        }
        if (Baritone.settings().buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) {
            this.schematic = new MirroredSchematic(this.schematic, Baritone.settings().buildSchematicMirror.value);
        }
        if (Baritone.settings().buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) {
            this.schematic = new RotatedSchematic(this.schematic, Baritone.settings().buildSchematicRotation.value);
        }
        // TODO this preserves the old behavior, but maybe we should bake the setting value right here
        this.schematic = new MaskSchematic(this.schematic) {
            @Override
            public boolean partOfMask(int x, int y, int z, BlockState current) {
                // partOfMask is only called inside the schematic so desiredState is not null
                return !Baritone.settings().buildSkipBlocks.value.contains(this.desiredState(x, y, z, current, Collections.emptyList()).getBlock());
            }
        };
        int x = origin.getX();
        int y = origin.getY();
        int z = origin.getZ();
        if (Baritone.settings().schematicOrientationX.value) {
            x += schematic.widthX();
        }
        if (Baritone.settings().schematicOrientationY.value) {
            y += schematic.heightY();
        }
        if (Baritone.settings().schematicOrientationZ.value) {
            z += schematic.lengthZ();
        }
        this.origin = new Vec3i(x, y, z);
        this.paused = false;
        this.currentLayerMinYInclusive = 0;
        this.currentLayerMaxYInclusive = schematic.heightY() - 1;
        this.manualPhaseMinY = 0;
        this.manualPhaseMaxY = -1;
        this.layer = Baritone.settings().startAtLayer.value;
        this.stopAtHeight = schematic.heightY();
        if (Baritone.settings().buildOnlySelection.value && buildingSelectionSchematic) {  // currently redundant but safer maybe
            if (baritone.getSelectionManager().getSelections().length == 0) {
                logDirect("Poor little kitten forgot to set a selection while BuildOnlySelection is true");
                this.stopAtHeight = 0;
            } else if (Baritone.settings().buildInLayers.value) {
                OptionalInt minim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.min().y).min();
                OptionalInt maxim = Stream.of(baritone.getSelectionManager().getSelections()).mapToInt(sel -> sel.max().y).max();
                if (minim.isPresent() && maxim.isPresent()) {
                    int startAtHeight = Baritone.settings().layerOrder.value ? y + schematic.heightY() - maxim.getAsInt() : minim.getAsInt() - y;
                    this.stopAtHeight = (Baritone.settings().layerOrder.value ? y + schematic.heightY() - minim.getAsInt() : maxim.getAsInt() - y) + 1;
                    this.layer = Math.max(this.layer, startAtHeight / Baritone.settings().layerHeight.value);  // startAtLayer or startAtHeight, whichever is highest
                    logDebug(String.format("Schematic starts at y=%s with height %s", y, schematic.heightY()));
                    logDebug(String.format("Selection starts at y=%s and ends at y=%s", minim.getAsInt(), maxim.getAsInt()));
                    logDebug(String.format("Considering relevant height %s - %s", startAtHeight, this.stopAtHeight));
                }
            }
        }

        this.numRepeats = 0;
        this.observedCompleted = new LongOpenHashSet();
        this.incorrectPositions = null;
    }

    private void resetPathFailureTracking() {
        lastRequestedGoal = null;
        consecutivePathFailures = 0;
        lastFailureObservedPlayerPos = null;
        lastFailureIncorrectPositionsSize = 0;
    }

    public void resume() {
        resetPathFailureTracking();
        placementAttempts.clear();
        placementApproachFailures.clear();
        toggleAttempts.clear();
        paused = false;
    }

    public void pause() {
        resetPathFailureTracking();
        paused = true;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try (FileInputStream input = new FileInputStream(schematic)) {
            parsed = format.get().parse(input);
        } catch (Exception exception) {
            logDirect("Failed to load schematic " + schematic.getName() + ": " + exception.getMessage());
            logDebug("Schematic parsing error: " + exception);
            return false;
        }
        ISchematic schem = applyMapArtAndSelection(origin, parsed);
        // Wrappers can hide the concrete loader type; warn before applying them when one was introduced.
        if (schem != parsed) {
            reportUnsupportedLitematicaPayload(parsed);
        }
        build(name, schem, origin);
        return true;
    }

    private ISchematic applyMapArtAndSelection(Vec3i origin, IStaticSchematic parsed) {
        ISchematic schematic = parsed;
        if (Baritone.settings().mapArtMode.value) {
            schematic = new MapArtSchematic(parsed);
        }
        if (Baritone.settings().buildOnlySelection.value) {
            schematic = new SelectionSchematic(schematic, origin, baritone.getSelectionManager().getSelections());
        }
        return schematic;
    }

    @Override
    public void buildOpenSchematic() {
        if (SchematicaHelper.isSchematicaPresent()) {
            Optional<Tuple<IStaticSchematic, BlockPos>> schematic = SchematicaHelper.getOpenSchematic();
            if (schematic.isPresent()) {
                IStaticSchematic raw = schematic.get().getA();
                BlockPos origin = schematic.get().getB();
                ISchematic schem = applyMapArtAndSelection(origin, raw);
                this.build(raw.toString(), schem, origin);
            } else {
                logDirect("No schematic currently open");
            }
        } else {
            logDirect("Schematica is not present");
        }
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (LitematicaHelper.isLitematicaPresent()) {
            //if java.lang.NoSuchMethodError is thrown see comment in SchematicPlacementManager
            if (LitematicaHelper.hasLoadedSchematic(i)) {
                Tuple<IStaticSchematic, Vec3i> schematic = LitematicaHelper.getSchematic(i);
                Vec3i correctedOrigin = schematic.getB();
                IStaticSchematic raw = schematic.getA();
                ISchematic schematic2 = applyMapArtAndSelection(correctedOrigin, raw);
                if (schematic2 != raw) {
                    reportUnsupportedLitematicaPayload(raw);
                }
                build(raw.toString(), schematic2, correctedOrigin);
            } else {
                logDirect(String.format("List of placements has no entry %s", i + 1));
            }
        } else {
            logDirect("Litematica is not present");
        }
    }

    public void clearArea(BlockPos corner1, BlockPos corner2) {
        BlockPos origin = new BlockPos(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        int widthX = Math.abs(corner1.getX() - corner2.getX()) + 1;
        int heightY = Math.abs(corner1.getY() - corner2.getY()) + 1;
        int lengthZ = Math.abs(corner1.getZ() - corner2.getZ()) + 1;
        build("clear area", new FillSchematic(widthX, heightY, lengthZ, Blocks.AIR.defaultBlockState()), origin);
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return new ArrayList<>(approxPlaceable);
    }

    @Override
    public boolean isActive() {
        return schematic != null;
    }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (!isActive()) {
            return null;
        }
        if (!schematic.inSchematic(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current)) {
            return null;
        }
        BlockState state = schematic.desiredState(x - origin.getX(), y - origin.getY(), z - origin.getZ(), current, this.approxPlaceable);
        if (state.getBlock() instanceof AirBlock) {
            return null;
        }
        return state;
    }

    static boolean isStructuralWorkMismatch(BlockState current, BlockState desired,
            boolean ignoreDirection, List<String> ignoredProperties) {
        if (desired == null || sameBlockstate(current, desired, ignoreDirection, ignoredProperties)) {
            return false;
        }
        return !needsManualInteraction(current, desired, ignoreDirection, ignoredProperties)
                && !isExternallyPoweredOpenableMismatch(current, desired, ignoreDirection, ignoredProperties);
    }

    private boolean canBeginManualInteractions(BuilderCalculationContext bcc) {
        if (incorrectPositions == null || incorrectPositions.isEmpty()) {
            return true;
        }
        for (BlockPos pos : incorrectPositions) {
            BlockState current = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
            if (isStructuralWorkMismatch(
                    current, desired,
                    Baritone.settings().buildIgnoreDirection.value,
                    Baritone.settings().buildIgnoreProperties.value)) {
                // Any ordinary placement, removal, missing material, or unsupported block
                // keeps stable circuit/state interactions deferred.
                return false;
            }
        }
        return true;
    }

    private Optional<Tuple<BetterBlockPos, Rotation>> toToggleNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -1; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BetterBlockPos pos = new BetterBlockPos(center.x + dx, center.y + dy, center.z + dz);
                    BlockState current = bcc.bsi.get0(pos);
                    BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
                    if (desired == null || !needsManualInteraction(current, desired)
                            || isToggleAttemptExhausted(pos, current, desired)) {
                        continue;
                    }
                    ToggleAttempt attempt = toggleAttempts.get(pos);
                    if (attempt != null && builderTick < attempt.retryAtTick) {
                        continue;
                    }
                    Optional<Rotation> rotation = RotationUtils.reachable(
                            ctx, pos, ctx.playerController().getBlockReachDistance()
                    );
                    if (rotation.isPresent()) {
                        return Optional.of(new Tuple<>(pos, rotation.get()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Tuple<BetterBlockPos, Rotation>> toBreakNearPlayer(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        BetterBlockPos pathStart = baritone.getPathingBehavior().pathStart();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = Baritone.settings().breakFromAbove.value ? -1 : 0; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    if (dy == -1 && x == pathStart.x && z == pathStart.z) {
                        continue; // dont mine what we're supported by, but not directly standing on
                    }
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (!(curr.getBlock() instanceof AirBlock) && !(curr.getBlock() == Blocks.WATER || curr.getBlock() == Blocks.LAVA)
                            && !valid(curr, desired, false) && !needsManualInteraction(curr, desired)
                            && !isExternallyPoweredOpenableMismatch(curr, desired)) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
                        if (rot.isPresent()) {
                            return Optional.of(new Tuple<>(pos, rot.get()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static class Placement {

        private final int hotbarSelection;
        private final BlockPos target;
        private final BlockPos placeAgainst;
        private final Direction side;
        private final Rotation rot;

        public Placement(int hotbarSelection, BlockPos target, BlockPos placeAgainst, Direction side, Rotation rot) {
            this.hotbarSelection = hotbarSelection;
            this.target = target;
            this.placeAgainst = placeAgainst;
            this.side = side;
            this.rot = rot;
        }
    }

    static boolean targetIntersectsPlayerBounds(BlockPos target, AABB playerBounds) {
        AABB targetBounds = new AABB(
                target.getX(), target.getY(), target.getZ(),
                target.getX() + 1.0, target.getY() + 1.0, target.getZ() + 1.0
        );
        return targetBounds.intersects(playerBounds);
    }

    static boolean isPotentialPlacementApproach(BlockPos target, BlockPos feet) {
        if (target.equals(feet)) {
            return false;
        }
        int dx = feet.getX() - target.getX();
        int dy = feet.getY() - target.getY();
        int dz = feet.getZ() - target.getZ();
        return Math.abs(dx) + Math.abs(dy < 0 ? dy + 1 : dy) + Math.abs(dz) <= 1;
    }

    private Map<BlockPos, BlockState> getAdjacentBlockStates(BlockPos target) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = target.relative(direction);
            states.put(new BlockPos(neighbor), ctx.world().getBlockState(neighbor));
        }
        return states;
    }

    private boolean approachFailureSnapshotMatches(PlacementApproachFailure failure, BlockState current,
            BlockState desired, Map<BlockPos, BlockState> surroundingStates) {
        return failure != null
                && failure.targetState.equals(current)
                && sameBlockstate(failure.desiredState, desired)
                && failure.surroundingStates.equals(surroundingStates);
    }

    private void recordPlacementApproachFailure(BlockPos target, BlockState current,
            BlockState desired, BlockPos feet) {
        if (!isPotentialPlacementApproach(target, feet)
                || targetIntersectsPlayerBounds(target, ctx.player().getBoundingBox())) {
            return;
        }
        boolean hasHotbarCandidate = false;
        for (int slot = 0; slot < 9 && slot < approxPlaceable.size(); slot++) {
            if (itemCouldPotentiallyPlace(approxPlaceable.get(slot), desired)) {
                hasHotbarCandidate = true;
                break;
            }
        }
        if (!hasHotbarCandidate) {
            return;
        }

        Map<BlockPos, BlockState> adjacentStates = getAdjacentBlockStates(target);
        PlacementApproachFailure failure = placementApproachFailures.get(target);
        if (!approachFailureSnapshotMatches(failure, current, desired, adjacentStates)) {
            if (failure == null && placementApproachFailures.size() >= MAX_PLACEMENT_APPROACH_FAILURES) {
                Iterator<BlockPos> oldest = placementApproachFailures.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            failure = new PlacementApproachFailure(current, desired, adjacentStates);
            placementApproachFailures.put(new BlockPos(target), failure);
        }
        failure.failedFeetPositions.add(new BlockPos(feet));
    }

    private Set<BlockPos> failedPlacementApproaches(BlockPos target, BlockState current, BlockState desired) {
        PlacementApproachFailure failure = placementApproachFailures.get(target);
        if (failure == null) {
            return Collections.emptySet();
        }
        if (!approachFailureSnapshotMatches(failure, current, desired, getAdjacentBlockStates(target))) {
            placementApproachFailures.remove(target);
            return Collections.emptySet();
        }
        return new HashSet<>(failure.failedFeetPositions);
    }

    private void cleanupPlacementApproachFailures(BuilderCalculationContext bcc) {
        Iterator<Map.Entry<BlockPos, PlacementApproachFailure>> iterator =
                placementApproachFailures.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, PlacementApproachFailure> entry = iterator.next();
            BlockPos target = entry.getKey();
            BlockState current = bcc.bsi.get0(target);
            BlockState desired = bcc.getSchematic(target.getX(), target.getY(), target.getZ(), current);
            if (desired == null || valid(current, desired, false)
                    || !approachFailureSnapshotMatches(
                            entry.getValue(), current, desired, getAdjacentBlockStates(target))) {
                iterator.remove();
            }
        }
    }

    private Optional<Placement> searchForPlacables(BuilderCalculationContext bcc, List<BlockState> desirableOnHotbar) {
        BetterBlockPos center = ctx.playerFeet();
        AABB playerBounds = ctx.player().getBoundingBox();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 1; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired == null) {
                        continue; // irrelevant
                    }
                    BlockState curr = bcc.bsi.get0(x, y, z);
                    if (MovementHelper.isReplaceable(x, y, z, curr, bcc.bsi) && !valid(curr, desired, false)) {
                        BlockPos target = new BetterBlockPos(x, y, z);
                        if (isPlacementAttemptExhausted(target, curr, desired)) {
                            continue;
                        }
                        // Skip only blocks whose actual unit bounds overlap the player; the old
                        // height-only check also skipped horizontally adjacent head-height wall blocks.
                        if (targetIntersectsPlayerBounds(target, playerBounds)) {
                            continue;
                        }
                        if (isPlacementAttemptWaiting(target, curr, desired)) {
                            continue;
                        }
                        desirableOnHotbar.add(desired);
                        Optional<Placement> opt = possibleToPlace(desired, x, y, z, bcc.bsi);
                        if (opt.isPresent()) {
                            return opt;
                        }
                        recordPlacementApproachFailure(target, curr, desired, center);
                    }
                }
            }
        }
        return Optional.empty();
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        try {
            VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
            return voxelshape.isEmpty() || ctx.world().isUnobstructed(
                    null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ())
            );
        } catch (RuntimeException exception) {
            logDebug("Unable to check placement collision at " + pos + " for " + state + ": " + exception);
            return false;
        }
    }

    private VoxelShape getOutlineShapeOrNull(BlockState state, BlockPos pos) {
        try {
            return state.getShape(ctx.world(), pos);
        } catch (RuntimeException exception) {
            logDebug("Unable to inspect support shape at " + pos + " for " + state + ": " + exception);
            return null;
        }
    }

    private List<AABB> getShapeBoxesOrEmpty(VoxelShape shape, BlockPos supportPos) {
        try {
            return shape.toAabbs();
        } catch (RuntimeException exception) {
            logDebug("Unable to inspect support collision boxes at " + supportPos + ": " + exception);
            return Collections.emptyList();
        }
    }

    private boolean canSurviveAt(BlockState state, BlockPos pos) {
        try {
            return state.canSurvive(ctx.world(), pos);
        } catch (RuntimeException exception) {
            logDebug("Unable to validate support requirements for " + state + " at " + pos + ": " + exception);
            return false;
        }
    }

    private Optional<Placement> possibleToPlace(BlockState toPlace, int x, int y, int z, BlockStateInterface bsi) {
        BlockPos targetPos = new BetterBlockPos(x, y, z);
        // Both checks depend on the target/world state, not on which adjacent support face we inspect.
        if (!canSurviveAt(toPlace, targetPos) || !placementPlausible(targetPos, toPlace)) {
            return Optional.empty();
        }
        for (Direction against : Direction.values()) {
            BetterBlockPos placeAgainstPos = new BetterBlockPos(x, y, z).relative(against);
            // 'against' points from the target toward its support; the clicked face points back toward the target.
            Direction clickedFace = supportFaceForTargetDirection(against);
            BlockState placeAgainstState = bsi.get0(placeAgainstPos);
            if (MovementHelper.isReplaceable(placeAgainstPos.x, placeAgainstPos.y, placeAgainstPos.z, placeAgainstState, bsi)) {
                continue;
            }
            VoxelShape shape = getOutlineShapeOrNull(placeAgainstState, placeAgainstPos);
            if (shape == null || shape.isEmpty()) {
                continue;
            }
            // Sample each component box, not only the overall bounds: stairs and other partial shapes
            // can have empty gaps inside their union bounding box.
            for (AABB aabb : getShapeBoxesOrEmpty(shape, placeAgainstPos)) {
                for (Vec3 placementMultiplier : aabbSideMultipliers(clickedFace)) {
                    double placeX = placeAgainstPos.x + sampleCoordinate(aabb.minX, aabb.maxX, placementMultiplier.x);
                    double placeY = placeAgainstPos.y + sampleCoordinate(aabb.minY, aabb.maxY, placementMultiplier.y);
                    double placeZ = placeAgainstPos.z + sampleCoordinate(aabb.minZ, aabb.maxZ, placementMultiplier.z);
                    Rotation rot = RotationUtils.calcRotationFromVec3d(RayTraceUtils.inferSneakingEyePosition(ctx.player()), new Vec3(placeX, placeY, placeZ), ctx.playerRotations());
                    Rotation actualRot = baritone.getLookBehavior().getAimProcessor().peekRotation(rot);
                    HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), actualRot, ctx.playerController().getBlockReachDistance(), true);
                    if (result != null && result.getType() == HitResult.Type.BLOCK && ((BlockHitResult) result).getBlockPos().equals(placeAgainstPos) && ((BlockHitResult) result).getDirection() == clickedFace) {
                        OptionalInt hotbar = hasAnyItemThatWouldPlace(toPlace, result, actualRot);
                        if (hotbar.isPresent()) {
                            // Store the aim-processor-adjusted rotation that was actually raycast-verified.
                            return Optional.of(new Placement(hotbar.getAsInt(), new BetterBlockPos(x, y, z),
                                    placeAgainstPos, clickedFace, actualRot));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    static Direction supportFaceForTargetDirection(Direction targetToSupport) {
        return targetToSupport.getOpposite();
    }

    static boolean isAimedAtCorrectFace(HitResult hit, BlockPos expectedBlock, Direction expectedFace) {
        return hit != null
                && hit.getType() == HitResult.Type.BLOCK
                && ((BlockHitResult) hit).getBlockPos().equals(expectedBlock)
                && ((BlockHitResult) hit).getDirection() == expectedFace;
    }

    static boolean isAimedAtBlock(HitResult hit, BlockPos expectedBlock) {
        return hit != null && hit.getType() == HitResult.Type.BLOCK
                && ((BlockHitResult) hit).getBlockPos().equals(expectedBlock);
    }

    static boolean supportsManualOpenToggle(BlockState state) {
        if (state == null) {
            return false;
        }
        // Iron doors and iron trapdoors have no hand-operated open action in vanilla.
        return (state.getBlock() instanceof DoorBlock && state.getBlock() != Blocks.IRON_DOOR)
                || (state.getBlock() instanceof TrapDoorBlock && state.getBlock() != Blocks.IRON_TRAPDOOR)
                || state.getBlock() instanceof FenceGateBlock;
    }

    static boolean isManualInteractionProperty(BlockState state, Property<?> property) {
        String name = property.getName();
        if ("open".equals(name)) {
            return supportsManualOpenToggle(state);
        }
        if ("powered".equals(name) && state.getBlock() instanceof LeverBlock) {
            return true;
        }
        if ("note".equals(name) && state.getBlock() instanceof NoteBlock) {
            return true;
        }
        if ("inverted".equals(name) && state.getBlock() instanceof DaylightDetectorBlock) {
            return true;
        }
        if ("delay".equals(name) && state.getBlock() instanceof RepeaterBlock) {
            return true;
        }
        return "mode".equals(name) && state.getBlock() instanceof ComparatorBlock;
    }

    static boolean isExternallyPoweredOpenableMismatch(BlockState current, BlockState desired) {
        return isExternallyPoweredOpenableMismatch(
                current, desired,
                Baritone.settings().buildIgnoreDirection.value,
                Baritone.settings().buildIgnoreProperties.value
        );
    }

    static boolean isExternallyPoweredOpenableMismatch(BlockState current, BlockState desired,
            boolean ignoreDirection, List<String> ignoredProperties) {
        if (current == null || desired == null || current.getBlock() != desired.getBlock()) {
            return false;
        }
        if (!(current.getBlock() instanceof DoorBlock
                || current.getBlock() instanceof TrapDoorBlock
                || current.getBlock() instanceof FenceGateBlock)) {
            return false;
        }
        Property<?> openProperty = current.getBlock().getStateDefinition().getProperty("open");
        Property<?> poweredProperty = current.getBlock().getStateDefinition().getProperty("powered");
        if (openProperty == null || poweredProperty == null
                || ignoredProperties.contains(openProperty.getName())
                || Objects.equals(current.getValues().get(openProperty), desired.getValues().get(openProperty))) {
            return false;
        }
        boolean powered = Boolean.TRUE.equals(current.getValues().get(poweredProperty))
                || Boolean.TRUE.equals(desired.getValues().get(poweredProperty));
        boolean redstoneOnlyOpenable = current.getBlock() == Blocks.IRON_DOOR
                || current.getBlock() == Blocks.IRON_TRAPDOOR;
        if (!powered && !redstoneOnlyOpenable) {
            return false;
        }

        // Defer only a pure open-state mismatch controlled by the active signal. If another required
        // state is wrong (for example facing/hinge), the builder must still be able to replace it.
        for (Property<?> property : current.getValues().keySet()) {
            if (Objects.equals(current.getValues().get(property), desired.getValues().get(property))
                    || ignoredProperties.contains(property.getName())
                    || property.equals(openProperty)
                    || isDerivedStateProperty(current, property)
                    || (ignoreDirection && ORIENTATION_PROPS.contains(property)
                    && !isManualInteractionProperty(current, property))) {
                continue;
            }
            return false;
        }
        return true;
    }

    static boolean needsManualInteraction(BlockState current, BlockState desired) {
        return needsManualInteraction(
                current, desired,
                Baritone.settings().buildIgnoreDirection.value,
                Baritone.settings().buildIgnoreProperties.value
        );
    }

    static boolean needsManualInteraction(BlockState current, BlockState desired,
            boolean ignoreDirection, List<String> ignoredProperties) {
        if (current == null || desired == null || current.getBlock() != desired.getBlock()
                || isExternallyPoweredOpenableMismatch(
                        current, desired, ignoreDirection, ignoredProperties)) {
            return false;
        }
        boolean hasToggleDifference = false;
        for (Property<?> property : current.getValues().keySet()) {
            if (Objects.equals(current.getValues().get(property), desired.getValues().get(property))
                    || ignoredProperties.contains(property.getName())) {
                continue;
            }
            boolean manualInteractionProperty = isManualInteractionProperty(current, property);
            if (ignoreDirection && ORIENTATION_PROPS.contains(property) && !manualInteractionProperty) {
                continue;
            }
            if (isDerivedStateProperty(current, property)) {
                continue; // externally powered/neighbor-derived state is not a manual toggle requirement
            }
            if (!manualInteractionProperty) {
                return false;
            }
            hasToggleDifference = true;
        }
        return hasToggleDifference;
    }

    static boolean toggleAttemptStateUnchanged(BlockState original, BlockState current,
            BlockState originalDesired, BlockState currentDesired) {
        return original != null && current != null && originalDesired != null && currentDesired != null
                && original.equals(current) && originalDesired.equals(currentDesired);
    }

    private void recordToggleAttempt(BlockPos pos, BlockState current, BlockState desired) {
        ToggleAttempt attempt = toggleAttempts.get(pos);
        if (attempt == null || !toggleAttemptStateUnchanged(
                attempt.originalState, current, attempt.desiredState, desired)) {
            attempt = new ToggleAttempt(current, desired);
            toggleAttempts.put(new BlockPos(pos), attempt);
        }
        attempt.attempts++;
        attempt.retryAtTick = builderTick + placementRetryDelayTicks(attempt.attempts);
    }

    private boolean isToggleAttemptWaiting(BlockPos pos, BlockState current, BlockState desired) {
        ToggleAttempt attempt = toggleAttempts.get(pos);
        return attempt != null
                && builderTick < attempt.retryAtTick
                && toggleAttemptStateUnchanged(attempt.originalState, current, attempt.desiredState, desired);
    }

    private boolean isToggleAttemptExhausted(BlockPos pos, BlockState current, BlockState desired) {
        ToggleAttempt attempt = toggleAttempts.get(pos);
        return attempt != null && attempt.attempts >= MAX_PLACEMENT_ATTEMPTS
                && builderTick >= attempt.retryAtTick
                && toggleAttemptStateUnchanged(attempt.originalState, current, attempt.desiredState, desired);
    }

    private void cleanupToggleAttempts(BuilderCalculationContext bcc) {
        Iterator<Map.Entry<BlockPos, ToggleAttempt>> iterator = toggleAttempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, ToggleAttempt> entry = iterator.next();
            BlockPos pos = entry.getKey();
            ToggleAttempt attempt = entry.getValue();
            BlockState current = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
            if (!needsManualInteraction(current, desired)
                    || !toggleAttemptStateUnchanged(attempt.originalState, current, attempt.desiredState, desired)) {
                iterator.remove();
            }
        }
    }

    private void recordPlacementAttempt(BlockPos target, BlockState desired, BlockPos supportPos) {
        BlockState current = ctx.world().getBlockState(target);
        BlockState supportState = ctx.world().getBlockState(supportPos);
        PlacementAttempt attempt = placementAttempts.get(target);
        if (attempt == null || !attempt.originalState.equals(current)
                || !sameBlockstate(attempt.desiredState, desired)
                || !attempt.supportPos.equals(supportPos)
                || !attempt.originalSupportState.equals(supportState)) {
            attempt = new PlacementAttempt(current, desired, supportPos, supportState);
            placementAttempts.put(new BlockPos(target), attempt);
        }
        attempt.attempts++;
        attempt.retryAtTick = builderTick + placementRetryDelayTicks(attempt.attempts);
    }

    /**
     * Clears attempts when the target succeeds, changes, or no longer belongs to the active schematic.
     * Exhausted attempts are deferred by assemble() so the builder can make progress elsewhere first.
     */
    private void cleanupPlacementAttempts(BuilderCalculationContext bcc) {
        Iterator<Map.Entry<BlockPos, PlacementAttempt>> iterator = placementAttempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, PlacementAttempt> entry = iterator.next();
            BlockPos pos = entry.getKey();
            PlacementAttempt attempt = entry.getValue();
            BlockState current = bcc.bsi.get0(pos);
            BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
            BlockState supportState = bcc.bsi.get0(attempt.supportPos);

            if (desired == null || !sameBlockstate(attempt.desiredState, desired) || valid(current, desired, false)
                    || !current.equals(attempt.originalState)
                    || !supportState.equals(attempt.originalSupportState)) {
                iterator.remove();
            }
        }
    }

    private boolean isPlacementAttemptWaiting(BlockPos pos, BlockState current, BlockState desired) {
        PlacementAttempt attempt = placementAttempts.get(pos);
        return attempt != null
                && builderTick < attempt.retryAtTick
                && current.equals(attempt.originalState)
                && sameBlockstate(attempt.desiredState, desired)
                && ctx.world().getBlockState(attempt.supportPos).equals(attempt.originalSupportState);
    }

    private boolean isPlacementAttemptExhausted(BlockPos pos, BlockState current, BlockState desired) {
        PlacementAttempt attempt = placementAttempts.get(pos);
        return attempt != null
                && attempt.attempts >= MAX_PLACEMENT_ATTEMPTS
                && builderTick >= attempt.retryAtTick
                && current.equals(attempt.originalState)
                && sameBlockstate(attempt.desiredState, desired);
    }

    private OptionalInt hasAnyItemThatWouldPlace(BlockState desired, HitResult result, Rotation rot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            float originalYaw = ctx.player().getYRot();
            float originalPitch = ctx.player().getXRot();
            BlockState wouldBePlaced;
            BlockPlaceContext placementContext;
            // Placement state can depend on the player's facing. Always restore the real rotation,
            // even if a modded BlockItem throws while simulating placement.
            try {
                ctx.player().setYRot(rot.getYaw());
                ctx.player().setXRot(rot.getPitch());
                placementContext = new BlockPlaceContext(new UseOnContext(
                        ctx.world(),
                        ctx.player(),
                        InteractionHand.MAIN_HAND,
                        stack,
                        (BlockHitResult) result
                ) {}); // that {} gives us access to a protected constructor
                wouldBePlaced = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(placementContext);
            } catch (RuntimeException exception) {
                // A broken modded block's prediction must not abort the entire builder tick.
                logDebug("Unable to predict placement for hotbar slot " + i + ": " + exception);
                continue;
            } finally {
                ctx.player().setYRot(originalYaw);
                ctx.player().setXRot(originalPitch);
            }
            if (wouldBePlaced == null) {
                continue;
            }
            try {
                if (!placementContext.canPlace()) {
                    continue;
                }
            } catch (RuntimeException exception) {
                // Some modded placement contexts consult additional world/inventory state and may fail too.
                logDebug("Unable to validate placement context for hotbar slot " + i + ": " + exception);
                continue;
            }
            if (isCompatiblePlacementPrediction(
                    wouldBePlaced, desired,
                    Baritone.settings().buildIgnoreDirection.value,
                    Baritone.settings().buildIgnoreProperties.value)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    static double sampleCoordinate(double min, double max, double multiplier) {
        // A multiplier of 0 samples the minimum face, 1 the maximum face, and 0.5 the midpoint.
        return min + (max - min) * multiplier;
    }

    static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        builderTick++;
        return onTick(calcFailed, isSafeToCancel, 0);
    }

    private PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, int recursions) {
        if (recursions > 100) { // onTick calls itself, don't crash
            return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
        }
        approxPlaceable = approxPlaceable(36);
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)) {
            ticks = 5;
        } else {
            ticks--;
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        if (paused) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (Baritone.settings().buildInLayers.value) {
            if (realSchematic == null) {
                realSchematic = schematic;
            }
            ISchematic realSchematic = this.realSchematic; // wrap this properly, dont just have the inner class refer to the builderprocess.this
            int minYInclusive = structuralLayerMinYInclusive(
                    realSchematic.heightY(), layer, Baritone.settings().layerHeight.value,
                    Baritone.settings().layerOrder.value, stopAtHeight
            );
            int maxYInclusive = structuralLayerMaxYInclusive(
                    realSchematic.heightY(), layer, Baritone.settings().layerHeight.value,
                    Baritone.settings().layerOrder.value, stopAtHeight
            );
            currentLayerMinYInclusive = minYInclusive;
            currentLayerMaxYInclusive = maxYInclusive;
            schematic = new ISchematic() {
                @Override
                public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                    return realSchematic.desiredState(x, y, z, current, BuilderProcess.this.approxPlaceable);
                }

                @Override
                public boolean inSchematic(int x, int y, int z, BlockState currentState) {
                    return ISchematic.super.inSchematic(x, y, z, currentState)
                            && isYIncludedInCurrentPass(
                                    y, minYInclusive, maxYInclusive,
                                    manualInteractionPhase, manualPhaseMinY, manualPhaseMaxY
                            )
                            && realSchematic.inSchematic(x, y, z, currentState);
                }

                @Override
                public void reset() {
                    realSchematic.reset();
                }

                @Override
                public int widthX() {
                    return realSchematic.widthX();
                }

                @Override
                public int heightY() {
                    return realSchematic.heightY();
                }

                @Override
                public int lengthZ() {
                    return realSchematic.lengthZ();
                }
            };
        }
        BuilderCalculationContext bcc = new BuilderCalculationContext();
        cleanupPlacementAttempts(bcc);
        cleanupPlacementApproachFailures(bcc);
        cleanupToggleAttempts(bcc);
        if (!recalc(bcc)) {
            if (!manualInteractionPhase) {
                if (Baritone.settings().buildInLayers.value
                        && hasMoreStructuralLayers(
                                layer, Baritone.settings().layerHeight.value, stopAtHeight, schematic.heightY()
                        )) {
                    // Finish every structural layer before activating any stable circuit controls.
                    logDirect("Starting layer " + layer);
                    layer++;
                    incorrectPositions = null;
                    placementAttempts.clear();
        placementApproachFailures.clear();
                    toggleAttempts.clear();
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                // The final interaction pass covers every layer that was actually built, including
                // earlier layers, but never extends past a user-selected stop height.
                manualPhaseMinY = manualPhaseMinYInclusive(
                        schematic.heightY(), currentLayerMinYInclusive,
                        Baritone.settings().buildInLayers.value,
                        Baritone.settings().layerOrder.value, stopAtHeight
                );
                manualPhaseMaxY = manualPhaseMaxYInclusive(
                        currentLayerMaxYInclusive,
                        Baritone.settings().buildInLayers.value,
                        Baritone.settings().layerOrder.value, stopAtHeight
                );
                manualInteractionPhase = true;
                for (long deferredPositionHash : deferredManualInteractionPositions) {
                    observedCompleted.remove(deferredPositionHash);
                }
                incorrectPositions = null;
                return onTick(calcFailed, isSafeToCancel, recursions + 1);
            }
            Vec3i repeat = Baritone.settings().buildRepeat.value;
            int max = Baritone.settings().buildRepeatCount.value;
            numRepeats++;
            if (repeat.equals(new Vec3i(0, 0, 0)) || (max != -1 && numRepeats >= max)) {
                logDirect("Done building");
                if (Baritone.settings().notificationOnBuildFinished.value) {
                    logNotification("Done building", false);
                }
                onLostControl();
                return null;
            }
            // build repeat time
            layer = 0;
            origin = new BlockPos(origin).offset(repeat);
            manualInteractionPhase = false;
            incorrectPositions = null;
            deferredManualInteractionPositions.clear();
            placementAttempts.clear();
        placementApproachFailures.clear();
            toggleAttempts.clear();
            if (!Baritone.settings().buildRepeatSneaky.value) {
                schematic.reset();
            }
            logDirect("Repeating build in vector " + repeat + ", new origin is " + origin);
            return onTick(calcFailed, isSafeToCancel, recursions + 1);
        }
        if (Baritone.settings().distanceTrim.value) {
            trim();
        }

        boolean noStructuralWork = manualInteractionPhase && canBeginManualInteractions(bcc);
        Optional<Tuple<BetterBlockPos, Rotation>> toToggle =
                manualInteractionsAllowed(manualInteractionPhase, noStructuralWork)
                        ? toToggleNearPlayer(bcc)
                        : Optional.empty();
        if (toToggle.isPresent() && isSafeToCancel && ctx.player().onGround()) {
            Rotation rot = toToggle.get().getB();
            BetterBlockPos pos = toToggle.get().getA();
            baritone.getLookBehavior().updateTarget(rot, true);
            if (isAimedAtBlock(ctx.objectMouseOver(), pos) && ticks <= 0) {
                // These blocks use a normal right-click to select the desired stable state.
                // Do not sneak: sneaking can suppress the block interaction and place the held item.
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                ticks = INITIAL_PLACEMENT_RETRY_TICKS;
                BlockState current = bcc.bsi.get0(pos);
                BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
                if (desired != null) {
                    recordToggleAttempt(pos, current, desired);
                }
            }
            lastRequestedGoal = null;
            consecutivePathFailures = 0;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        Optional<Tuple<BetterBlockPos, Rotation>> toBreak = toBreakNearPlayer(bcc);
        if (toBreak.isPresent() && isSafeToCancel && ctx.player().onGround()) {
            // we'd like to pause to break this block
            // only change look direction if it's safe (don't want to fuck up an in progress parkour for example
            Rotation rot = toBreak.get().getB();
            BetterBlockPos pos = toBreak.get().getA();
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, bcc.get(pos));
            if (ctx.player().isCrouching()) {
                // really horrible bug where a block is visible for breaking while sneaking but not otherwise
                // so you can't see it, it goes to place something else, sneaks, then the next tick it tries to break
                // and is unable since it's unsneaked in the intermediary tick
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            // Never force a break based on approximate rotation alone: the actual ray must hit the selected block.
            if (isAimedAtBlock(ctx.objectMouseOver(), pos)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            lastRequestedGoal = null;
            consecutivePathFailures = 0;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        List<BlockState> desirableOnHotbar = new ArrayList<>();
        Optional<Placement> toPlace = searchForPlacables(bcc, desirableOnHotbar);
        if (toPlace.isPresent() && isSafeToCancel && ctx.player().onGround() && ticks <= 0) {
            Rotation rot = toPlace.get().rot;
            baritone.getLookBehavior().updateTarget(rot, true);
            ctx.player().getInventory().setSelectedSlot(toPlace.get().hotbarSelection);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if (isAimedAtCorrectFace(ctx.objectMouseOver(), toPlace.get().placeAgainst, toPlace.get().side)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                ticks = INITIAL_PLACEMENT_RETRY_TICKS;
                BlockState desired = bcc.getSchematic(
                        toPlace.get().target.getX(), toPlace.get().target.getY(), toPlace.get().target.getZ(),
                        bcc.bsi.get0(toPlace.get().target)
                );
                if (desired != null) {
                    recordPlacementAttempt(toPlace.get().target, desired, toPlace.get().placeAgainst);
                }
            }
            lastRequestedGoal = null;
            consecutivePathFailures = 0;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (Baritone.settings().allowInventory.value) {
            ArrayList<Integer> usefulSlots = new ArrayList<>();
            List<BlockState> noValidHotbarOption = new ArrayList<>();
            outer:
            for (BlockState desired : desirableOnHotbar) {
                for (int i = 0; i < 9; i++) {
                    if (itemCouldPotentiallyPlace(approxPlaceable.get(i), desired)) {
                        usefulSlots.add(i);
                        continue outer;
                    }
                }
                noValidHotbarOption.add(desired);
            }

            outer:
            for (int i = 9; i < 36; i++) {
                for (BlockState desired : noValidHotbarOption) {
                    if (itemCouldPotentiallyPlace(approxPlaceable.get(i), desired)) {
                        if (!baritone.getInventoryBehavior().attemptToPutOnHotbar(i, usefulSlots::contains)) {
                            // Inventory has to be moved before a useful placement is possible; don't carry a stale path failure across the move.
                            resetPathFailureTracking();
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                        break outer;
                    }
                }
            }
        }

        Goal goal = assemble(bcc, approxPlaceable.subList(0, 9));
        if (goal == null) {
            goal = assemble(bcc, approxPlaceable, true); // we're far away, so assume that we have our whole inventory to recalculate placeable properly
            if (goal == null) {
                if (shouldSkipFailedStructuralLayer(
                        manualInteractionPhase,
                        Baritone.settings().skipFailedLayers.value,
                        Baritone.settings().buildInLayers.value,
                        layer,
                        Baritone.settings().layerHeight.value,
                        stopAtHeight,
                        schematic.heightY()
                )) {
                    logDirect("Skipping layer that I cannot construct! Layer #" + layer);
                    layer++;
                    return onTick(calcFailed, isSafeToCancel, recursions + 1);
                }
                logDirect("Unable to do it. Pausing. resume to resume, cancel to cancel");
                paused = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        BlockPos currentFeet = new BlockPos(ctx.playerFeet());
        int incorrectSize = incorrectPositions == null ? 0 : incorrectPositions.size();
        boolean progressChanged = lastFailureObservedPlayerPos != null
                && (!lastFailureObservedPlayerPos.equals(currentFeet)
                || lastFailureIncorrectPositionsSize != incorrectSize);
        consecutivePathFailures = nextGoalPathFailureCount(
                calcFailed, lastRequestedGoal, goal, consecutivePathFailures, progressChanged
        );
        if (consecutivePathFailures >= 2) {
            logDirect("No path to the remaining build placements after repeated attempts. Builder paused; check obstacles, reachability, and required support blocks.");
            paused = true;
            lastRequestedGoal = null;
            consecutivePathFailures = 0;
            lastFailureObservedPlayerPos = null;
            lastFailureIncorrectPositionsSize = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        lastRequestedGoal = goal;
        lastFailureObservedPlayerPos = currentFeet;
        lastFailureIncorrectPositionsSize = incorrectSize;
        return new PathingCommandContext(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH, bcc);
    }

    private boolean recalc(BuilderCalculationContext bcc) {
        if (incorrectPositions == null) {
            incorrectPositions = new HashSet<>();
            fullRecalc(bcc);
            if (incorrectPositions.isEmpty()) {
                return false;
            }
        }
        recalcNearby(bcc);
        if (incorrectPositions.isEmpty()) {
            fullRecalc(bcc);
        }
        return !incorrectPositions.isEmpty();
    }

    private void trim() {
        HashSet<BetterBlockPos> copy = new HashSet<>(incorrectPositions);
        copy.removeIf(pos -> pos.distSqr(ctx.player().blockPosition()) > 200);
        if (!copy.isEmpty()) {
            incorrectPositions = copy;
        }
    }

    private void recalcNearby(BuilderCalculationContext bcc) {
        BetterBlockPos center = ctx.playerFeet();
        int radius = Baritone.settings().builderTickScanRadius.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.x + dx;
                    int y = center.y + dy;
                    int z = center.z + dz;
                    BlockState desired = bcc.getSchematic(x, y, z, bcc.bsi.get0(x, y, z));
                    if (desired != null) {
                        // we care about this position
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        BlockState current = bcc.bsi.get0(x, y, z);
                        long positionHash = BetterBlockPos.longHash(pos);
                        if (validForCurrentBuildPhase(current, desired)) {
                            incorrectPositions.remove(pos);
                            if (!manualInteractionPhase && isDeferredManualStateMismatch(
                                    current, desired,
                                    Baritone.settings().buildIgnoreDirection.value,
                                    Baritone.settings().buildIgnoreProperties.value)) {
                                deferredManualInteractionPositions.add(positionHash);
                                // Structurally complete for this pass; requeue it at the final interaction boundary.
                                observedCompleted.add(positionHash);
                            } else {
                                if (valid(current, desired, false)) {
                                    deferredManualInteractionPositions.remove(positionHash);
                                }
                                observedCompleted.add(positionHash);
                            }
                        } else {
                            deferredManualInteractionPositions.remove(positionHash);
                            incorrectPositions.add(pos);
                            observedCompleted.remove(positionHash);
                        }
                    }
                }
            }
        }
    }

    private boolean validForCurrentBuildPhase(BlockState current, BlockState desired) {
        if (valid(current, desired, false)) {
            return true;
        }
        if (manualInteractionPhase) {
            return false;
        }
        return !isStructuralWorkMismatch(
                current, desired,
                Baritone.settings().buildIgnoreDirection.value,
                Baritone.settings().buildIgnoreProperties.value
        );
    }

    private void fullRecalc(BuilderCalculationContext bcc) {
        incorrectPositions = new HashSet<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int z = 0; z < schematic.lengthZ(); z++) {
                for (int x = 0; x < schematic.widthX(); x++) {
                    int blockX = x + origin.getX();
                    int blockY = y + origin.getY();
                    int blockZ = z + origin.getZ();
                    BlockState current = bcc.bsi.get0(blockX, blockY, blockZ);
                    if (!schematic.inSchematic(x, y, z, current)) {
                        continue;
                    }
                    if (bcc.bsi.worldContainsLoadedChunk(blockX, blockZ)) { // check if its in render distance, not if its in cache
                        // we can directly observe this block, it is in render distance
                        BlockState desired = schematic.desiredState(x, y, z, current, this.approxPlaceable);
                        BetterBlockPos pos = new BetterBlockPos(blockX, blockY, blockZ);
                        long positionHash = BetterBlockPos.longHash(pos);
                        if (validForCurrentBuildPhase(current, desired)) {
                            if (!manualInteractionPhase && isDeferredManualStateMismatch(
                                    current, desired,
                                    Baritone.settings().buildIgnoreDirection.value,
                                    Baritone.settings().buildIgnoreProperties.value)) {
                                deferredManualInteractionPositions.add(positionHash);
                                observedCompleted.add(positionHash);
                            } else {
                                if (valid(current, desired, false)) {
                                    deferredManualInteractionPositions.remove(positionHash);
                                }
                                observedCompleted.add(positionHash);
                            }
                        } else {
                            deferredManualInteractionPositions.remove(positionHash);
                            incorrectPositions.add(pos);
                            observedCompleted.remove(BetterBlockPos.longHash(blockX, blockY, blockZ));
                            if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                                return;
                            }
                        }
                        continue;
                    }
                    // this is not in render distance
                    if (!observedCompleted.contains(BetterBlockPos.longHash(blockX, blockY, blockZ))) {
                        // and we've never seen this position be correct
                        // therefore mark as incorrect
                        incorrectPositions.add(new BetterBlockPos(blockX, blockY, blockZ));
                        if (incorrectPositions.size() > Baritone.settings().incorrectSize.value) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable) {
        return assemble(bcc, approxPlaceable, false);
    }

    private Goal assemble(BuilderCalculationContext bcc, List<BlockState> approxPlaceable, boolean logMissing) {
        List<BetterBlockPos> placeable = new ArrayList<>();
        List<BetterBlockPos> interactable = new ArrayList<>();
        List<BetterBlockPos> deferredForSupport = new ArrayList<>();
        List<BetterBlockPos> deferredForPlacementFailure = new ArrayList<>();
        List<BetterBlockPos> waitingForPlacementRetry = new ArrayList<>();
        List<BetterBlockPos> waitingForInteractionRetry = new ArrayList<>();
        List<BetterBlockPos> deferredForInteraction = new ArrayList<>();
        List<BetterBlockPos> deferredForExternalPower = new ArrayList<>();
        List<BetterBlockPos> breakable = new ArrayList<>();
        List<BetterBlockPos> sourceLiquids = new ArrayList<>();
        List<BetterBlockPos> flowingLiquids = new ArrayList<>();
        Map<BlockState, Integer> missing = new HashMap<>();
        List<BetterBlockPos> outOfBounds = new ArrayList<>();
        incorrectPositions.forEach(pos -> {
            BlockState state = bcc.bsi.get0(pos);
            if (state.getBlock() instanceof AirBlock) {
                BlockState desired = bcc.getSchematic(pos.x, pos.y, pos.z, state);
                if (desired == null) {
                    outOfBounds.add(pos);
                } else if (containsBlockState(approxPlaceable, desired)) {
                    if (isPlacementAttemptExhausted(pos, state, desired)) {
                        deferredForPlacementFailure.add(pos);
                    } else if (isPlacementAttemptWaiting(pos, state, desired)) {
                        waitingForPlacementRetry.add(pos);
                    } else {
                        placeable.add(pos);
                    }
                } else {
                    missing.put(desired, 1 + missing.getOrDefault(desired, 0));
                }
            } else {
                BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), state);
                if (isExternallyPoweredOpenableMismatch(state, desired)) {
                    deferredForExternalPower.add(pos);
                } else if (needsManualInteraction(state, desired)) {
                    if (isToggleAttemptExhausted(pos, state, desired)) {
                        deferredForInteraction.add(pos);
                    } else if (isToggleAttemptWaiting(pos, state, desired)) {
                        waitingForInteractionRetry.add(pos);
                    } else {
                        interactable.add(pos);
                    }
                } else if (state.getBlock() instanceof LiquidBlock) {
                    // if the block itself is JUST a liquid (i.e. not just a waterlogged block), we CANNOT break it
                    // TODO for 1.13 make sure that this only matches pure water, not waterlogged blocks
                    if (!MovementHelper.possiblyFlowing(state)) {
                        // if it's a source block then we want to replace it with a throwaway
                        sourceLiquids.add(pos);
                    } else {
                        flowingLiquids.add(pos);
                    }
                } else {
                    breakable.add(pos);
                }
            }
        });
        incorrectPositions.removeAll(outOfBounds);
        List<Goal> toBreak = new ArrayList<>();
        breakable.forEach(pos -> toBreak.add(breakGoal(pos, bcc)));
        List<Goal> toPlace = new ArrayList<>();
        Set<BlockPos> placeablePositions = new HashSet<>(placeable);
        placeable.forEach(pos -> {
            if (placeablePositions.contains(pos.below()) || placeablePositions.contains(pos.below(2))) {
                return; // build lower dependencies first
            }
            BlockState desired = bcc.getSchematic(
                    pos.getX(), pos.getY(), pos.getZ(), bcc.bsi.get0(pos)
            );
            if (desired == null || !canSurviveAt(desired, pos)) {
                deferredForSupport.add(pos);
                return; // do not route to a dependent block until its support exists
            }
            toPlace.add(placementGoal(pos, bcc));
        });
        sourceLiquids.forEach(pos -> toPlace.add(new GoalBlock(pos.above())));

        if (!toPlace.isEmpty()) {
            return new JankyGoalComposite(new GoalComposite(toPlace.toArray(new Goal[0])), new GoalComposite(toBreak.toArray(new Goal[0])));
        }
        if (!toBreak.isEmpty()) {
            return new GoalComposite(toBreak.toArray(new Goal[0]));
        }

        // Activate stable manual states only after ordinary placements and removals are complete.
        // In particular, don't switch a lever while there are still blocks to build around its circuit.
        boolean structuralWorkPending = !placeable.isEmpty()
                || !deferredForSupport.isEmpty()
                || !deferredForPlacementFailure.isEmpty()
                || !waitingForPlacementRetry.isEmpty()
                || !missing.isEmpty()
                || !flowingLiquids.isEmpty();
        if (!structuralWorkPending && !interactable.isEmpty()) {
            List<Goal> interactionGoals = new ArrayList<>();
            interactable.forEach(pos -> interactionGoals.add(new GoalGetToBlock(pos)));
            return new GoalComposite(interactionGoals.toArray(new Goal[0]));
        }

        if (toBreak.isEmpty()) {
            if (logMissing && !deferredForPlacementFailure.isEmpty()) {
                String examples = deferredForPlacementFailure.stream()
                        .limit(5)
                        .map(p -> String.format("%s,%s,%s",
                                SettingsUtil.maybeCensor(p.getX()),
                                SettingsUtil.maybeCensor(p.getY()),
                                SettingsUtil.maybeCensor(p.getZ())))
                        .collect(Collectors.joining("; "));
                logDirect("Placement was repeatedly rejected at " + examples
                        + ". Check interaction reach, the support face, inventory, and server permissions.");
            }
            if (logMissing && !deferredForExternalPower.isEmpty()) {
                String examples = deferredForExternalPower.stream()
                        .limit(5)
                        .map(p -> String.format("%s,%s,%s",
                                SettingsUtil.maybeCensor(p.getX()),
                                SettingsUtil.maybeCensor(p.getY()),
                                SettingsUtil.maybeCensor(p.getZ())))
                        .collect(Collectors.joining("; "));
                logDirect("Open state remains controlled by an active redstone signal at " + examples
                        + ". Build or disable the power source before this block can match the schematic.");
            }
            if (logMissing && !deferredForInteraction.isEmpty()) {
                String examples = deferredForInteraction.stream()
                        .limit(5)
                        .map(p -> String.format("%s,%s,%s",
                                SettingsUtil.maybeCensor(p.getX()),
                                SettingsUtil.maybeCensor(p.getY()),
                                SettingsUtil.maybeCensor(p.getZ())))
                        .collect(Collectors.joining("; "));
                logDirect("Could not switch state of interactable blocks at " + examples
                        + " after repeated attempts. Check reach, interaction restrictions, or whether the block is being powered by another source.");
            }
            if (logMissing && !deferredForSupport.isEmpty()) {
                String examples = deferredForSupport.stream()
                        .limit(5)
                        .map(p -> String.format("%s,%s,%s",
                                SettingsUtil.maybeCensor(p.getX()),
                                SettingsUtil.maybeCensor(p.getY()),
                                SettingsUtil.maybeCensor(p.getZ())))
                        .collect(Collectors.joining("; "));
                logDirect("Remaining blocks need support or their support could not be verified at: " + examples
                        + ". Build the foundation/adjacent support first, or inspect the modded block's support rules.");
            }
            if (logMissing && !missing.isEmpty()) {
                logDirect("Missing materials for at least:");
                logDirect(missing.entrySet().stream()
                        .map(e -> String.format("%sx %s", e.getValue(), e.getKey()))
                        .collect(Collectors.joining("\n")));
            }
            if (logMissing && !flowingLiquids.isEmpty()) {
                logDirect("Unreplaceable liquids at at least:");
                logDirect(flowingLiquids.stream()
                        .map(p -> String.format("%s %s %s", p.x, p.y, p.z))
                        .collect(Collectors.joining("\n")));
            }
            if (!waitingForPlacementRetry.isEmpty() || !waitingForInteractionRetry.isEmpty()) {
                List<Goal> waitGoals = new ArrayList<>();
                waitingForPlacementRetry.forEach(pos -> waitGoals.add(new GoalGetToBlock(pos)));
                waitingForInteractionRetry.forEach(pos -> waitGoals.add(new GoalGetToBlock(pos)));
                return new GoalComposite(waitGoals.toArray(new Goal[0]));
            }
            return null;
        }
        return new GoalComposite(toBreak.toArray(new Goal[0]));
    }

    public static class JankyGoalComposite implements Goal {

        private final Goal primary;
        private final Goal fallback;

        public JankyGoalComposite(Goal primary, Goal fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }


        @Override
        public boolean isInGoal(int x, int y, int z) {
            return primary.isInGoal(x, y, z) || fallback.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return primary.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            JankyGoalComposite goal = (JankyGoalComposite) o;
            return Objects.equals(primary, goal.primary)
                    && Objects.equals(fallback, goal.fallback);
        }

        @Override
        public int hashCode() {
            int hash = -1701079641;
            hash = hash * 1196141026 + primary.hashCode();
            hash = hash * -80327868 + fallback.hashCode();
            return hash;
        }

        @Override
        public String toString() {
            return "JankyComposite Primary: " + primary + " Fallback: " + fallback;
        }
    }

    public static class GoalBreak extends GoalGetToBlock {

        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // can't stand right on top of a block, that might not work (what if it's unsupported, can't break then)
            if (y > this.y) {
                return false;
            }
            // but any other adjacent works for breaking, including inside or below
            return super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalBreak{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1636324008;
        }
    }

    static Direction[] placementSupportDirections() {
        // Include UP so ceiling-mounted controls and other underside placements can be approached from below.
        return Direction.values();
    }

    private Goal placementGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (!(ctx.world().getBlockState(pos).getBlock() instanceof AirBlock)) {  // TODO can this even happen?
            return new GoalPlace(pos);
        }
        boolean allowSameLevel = !(ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock);
        BlockState current = ctx.world().getBlockState(pos);
        BlockState desired = bcc.getSchematic(pos.getX(), pos.getY(), pos.getZ(), current);
        if (desired == null || !placementPlausible(pos, desired)) {
            return new GoalPlace(pos);
        }

        Set<BlockPos> excludedFeetPositions =
                new HashSet<>(failedPlacementApproaches(pos, current, desired));
        boolean hasPotentialSupport = false;
        for (Direction towardSupport : placementSupportDirections()) {
            BlockPos supportPos = pos.relative(towardSupport);
            //noinspection ConstantConditions
            if (MovementHelper.canPlaceAgainst(ctx, supportPos)) {
                hasPotentialSupport = true;
                // Avoid aiming for a position occupied by the support block; some partial
                // collision shapes may otherwise be accepted as a feet-level goal.
                excludedFeetPositions.add(new BlockPos(supportPos));
            }
        }
        if (!hasPotentialSupport) {
            return new GoalPlace(pos);
        }
        return new GoalPlacementApproach(pos, excludedFeetPositions, allowSameLevel);
    }

    private Goal breakGoal(BlockPos pos, BuilderCalculationContext bcc) {
        if (Baritone.settings().goalBreakFromAbove.value && bcc.bsi.get0(pos.above()).getBlock() instanceof AirBlock && bcc.bsi.get0(pos.above(2)).getBlock() instanceof AirBlock) { // TODO maybe possible without the up(2) check?
            return new JankyGoalComposite(new GoalBreak(pos), new GoalGetToBlock(pos.above()) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    if (y > this.y || (x == this.x && y == this.y && z == this.z)) {
                        return false;
                    }
                    return super.isInGoal(x, y, z);
                }
            });
        }
        return new GoalBreak(pos);
    }

    /**
     * A get-near-target goal that excludes feet positions already tried without finding a usable
     * placement face. The excluded list changes the goal identity so Baritone recalculates the path.
     */
    public static class GoalPlacementApproach extends GoalGetToBlock {
        private final Set<BlockPos> excludedFeetPositions;
        private final boolean allowSameLevel;

        public GoalPlacementApproach(BlockPos target, Set<BlockPos> excludedFeetPositions,
                boolean allowSameLevel) {
            super(target);
            this.excludedFeetPositions = excludedFeetPositions.stream()
                    .map(BlockPos::new)
                    .collect(Collectors.toCollection(HashSet::new));
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if ((x == this.x && y == this.y && z == this.z)
                    || excludedFeetPositions.contains(new BlockPos(x, y, z))
                    || (!allowSameLevel && y == this.y - 1)
                    || y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public boolean equals(Object other) {
            if (!super.equals(other) || !(other instanceof GoalPlacementApproach)) {
                return false;
            }
            GoalPlacementApproach goal = (GoalPlacementApproach) other;
            return allowSameLevel == goal.allowSameLevel
                    && excludedFeetPositions.equals(goal.excludedFeetPositions);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * super.hashCode() + Boolean.hashCode(allowSameLevel))
                    + excludedFeetPositions.hashCode();
        }

        @Override
        public String toString() {
            return "GoalPlacementApproach{" + x + "," + y + "," + z
                    + ", excluded=" + excludedFeetPositions.size() + "}";
        }
    }

    public static class GoalAdjacent extends GoalGetToBlock {

        private boolean allowSameLevel;
        private BlockPos no;

        public GoalAdjacent(BlockPos pos, BlockPos no, boolean allowSameLevel) {
            super(pos);
            this.no = no;
            this.allowSameLevel = allowSameLevel;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == this.x && y == this.y && z == this.z) {
                return false;
            }
            if (x == no.getX() && y == no.getY() && z == no.getZ()) {
                return false;
            }
            if (!allowSameLevel && y == this.y - 1) {
                return false;
            }
            if (y < this.y - 1) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) {
                return false;
            }

            GoalAdjacent goal = (GoalAdjacent) o;
            return allowSameLevel == goal.allowSameLevel
                    && Objects.equals(no, goal.no);
        }

        @Override
        public int hashCode() {
            int hash = 806368046;
            hash = hash * 1412661222 + super.hashCode();
            hash = hash * 1730799370 + (int) BetterBlockPos.longHash(no.getX(), no.getY(), no.getZ());
            hash = hash * 260592149 + (allowSameLevel ? -1314802005 : 1565710265);
            return hash;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalAdjacent{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public static class GoalPlace extends GoalBlock {

        public GoalPlace(BlockPos placeAt) {
            super(placeAt.above());
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // prioritize lower y coordinates
            return this.y * 100 + super.heuristic(x, y, z);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 1910811835;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalPlace{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    @Override
    public void onLostControl() {
        placementAttempts.clear();
        placementApproachFailures.clear();
        toggleAttempts.clear();
        deferredManualInteractionPositions.clear();
        builderTick = 0;
        manualInteractionPhase = false;
        lastRequestedGoal = null;
        consecutivePathFailures = 0;
        lastFailureObservedPlayerPos = null;
        lastFailureIncorrectPositionsSize = 0;
        incorrectPositions = null;
        name = null;
        schematic = null;
        realSchematic = null;
        layer = Baritone.settings().startAtLayer.value;
        numRepeats = 0;
        paused = false;
        observedCompleted = null;
    }

    @Override
    public String displayName0() {
        return paused ? "Builder Paused" : "Building " + name;
    }

    @Override
    public Optional<Integer> getMinLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.layer);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        if (Baritone.settings().buildInLayers.value) {
            return Optional.of(this.stopAtHeight);
        }
        return Optional.empty();
    }

    private List<BlockState> approxPlaceable(int size) {
        List<BlockState> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.player().getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                result.add(Blocks.AIR.defaultBlockState());
                continue;
            }
            BlockItem blockItem = (BlockItem) stack.getItem();
            BlockState fallbackState = blockItem.getBlock().defaultBlockState();
            try {
                BlockState itemState = blockItem.getBlock().getStateForPlacement(
                    new BlockPlaceContext(
                        new UseOnContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack,
                                new BlockHitResult(new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z),
                                        Direction.UP, ctx.playerFeet(), false)) {}
                    )
                );
                // A null prediction at the player's current position does not mean this item is useless elsewhere.
                result.add(itemState != null ? itemState : fallbackState);
            } catch (RuntimeException exception) {
                // This is advisory inventory analysis; retain the block identity even if its prediction throws.
                logDebug("Unable to approximate placement for inventory slot " + i + ": " + exception);
                result.add(fallbackState);
            }
        }
        return result;
    }

    /**
     * These values are derived by redstone updates or neighboring blocks rather than selected
     * reliably by a BlockItem's placement context. Treat them as runtime state during schematic
     * comparison so powered circuitry is not broken merely because it is currently active.
     */
    static boolean isDerivedStateProperty(BlockState state, Property<?> property) {
        String propertyName = property.getName();
        // A stable state that the player can directly toggle must remain visible to schematic comparison.
        // In particular, a lever's powered flag is manually controlled, not disposable runtime noise.
        if (isManualInteractionProperty(state, property)) {
            return false;
        }
        // Door power is externally driven even though the door's open state is not generally manual
        // when a redstone signal controls it.
        if ("powered".equals(propertyName)
                && (state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock)) {
            return true;
        }
        String blockClass = state.getBlock().getClass().getSimpleName();
        String normalizedClass = blockClass.toLowerCase(Locale.ROOT);
        if ("powered".equals(propertyName) && state.getBlock() instanceof LeverBlock) {
            return false; // lever state is selected by player interaction, not circuit power alone
        }
        if (state.getBlock() instanceof ChestBlock && "type".equals(propertyName)) {
            return true; // single/left/right chest state is derived from neighboring chests
        }
        if (state.getBlock() instanceof FenceGateBlock && "in_wall".equals(propertyName)) {
            return true; // this state is recalculated from adjacent walls
        }
        if (state.getBlock() instanceof NoteBlock
                && ("instrument".equals(propertyName) || "powered".equals(propertyName))) {
            return true; // instrument follows the block below; powered follows a redstone signal
        }
        if (DYNAMIC_STATE_PROPERTIES.contains(propertyName) && isRedstoneStateBlock(normalizedClass)) {
            return true;
        }
        if ("shape".equals(propertyName)
                && (state.getBlock() instanceof StairBlock || blockClass.contains("Rail"))) {
            return true;
        }
        if (!NEIGHBOR_CONNECTION_PROPERTIES.contains(propertyName)) {
            return false;
        }
        return state.getBlock() instanceof PipeBlock
                || blockClass.contains("Fence")
                || blockClass.contains("Wall")
                || blockClass.contains("Pane")
                || blockClass.contains("Bars")
                || blockClass.contains("Wire")
                || blockClass.contains("Vine")
                || blockClass.contains("ChorusPlant");
    }

    private static boolean isRedstoneStateBlock(String normalizedClass) {
        return normalizedClass.contains("redstone")
                || normalizedClass.contains("noteblock")
                || normalizedClass.contains("repeater")
                || normalizedClass.contains("comparator")
                || normalizedClass.contains("lever")
                || normalizedClass.contains("button")
                || normalizedClass.contains("pressureplate")
                || normalizedClass.contains("pressure_plate")
                || normalizedClass.contains("piston")
                || normalizedClass.contains("observer")
                || normalizedClass.contains("tripwire")
                || normalizedClass.contains("dispenser")
                || normalizedClass.contains("dropper")
                || normalizedClass.contains("hopper")
                || normalizedClass.contains("rail")
                || normalizedClass.contains("daylightdetector")
                || normalizedClass.contains("targetblock")
                || normalizedClass.contains("lightningrod")
                || normalizedClass.contains("sculksensor")
                || normalizedClass.contains("sculkshrieker")
                || normalizedClass.contains("copperbulb")
                || normalizedClass.contains("crafter");
    }

    static boolean isOpenableOpenProperty(BlockState state, Property<?> property) {
        return state != null
                && "open".equals(property.getName())
                && (state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock);
    }

    static boolean sameBlockstate(BlockState first, BlockState second) {
        return sameBlockstate(
                first, second,
                Baritone.settings().buildIgnoreDirection.value,
                Baritone.settings().buildIgnoreProperties.value
        );
    }

    static boolean sameBlockstate(BlockState first, BlockState second,
            boolean ignoreDirection, List<String> ignoredProps) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        Map<Property<?>, Comparable<?>> map1 = first.getValues();
        Map<Property<?>, Comparable<?>> map2 = second.getValues();
        if (!ignoreDirection && ignoredProps.isEmpty()) {
            boolean hasDerivedProperties = false;
            for (Property<?> property : map1.keySet()) {
                if (isDerivedStateProperty(first, property)) {
                    hasDerivedProperties = true;
                    break;
                }
            }
            if (!hasDerivedProperties) {
                return first.equals(second);
            }
        }
        for (Property<?> prop : map1.keySet()) {
            if (!Objects.equals(map1.get(prop), map2.get(prop))
                    && !isDerivedStateProperty(first, prop)
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop)
                    && !isManualInteractionProperty(first, prop)
                    && !isOpenableOpenProperty(first, prop))
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * This list is only an inventory heuristic: facing, half, axis, and neighbor-derived properties
     * can be chosen or updated at the eventual target. Exact state prediction happens in possibleToPlace.
     */
    /**
     * Placement state prediction runs before a block exists at the target, so properties that can be
     * selected by a later right-click (repeater delay, note, lever power, and similar stable states)
     * must not prevent placing the otherwise correct block. Structural/orientation mismatches still
     * fail here and are corrected by replacing the block rather than accepting the wrong geometry.
     */
    static boolean isCompatiblePlacementPrediction(BlockState predicted, BlockState desired,
            boolean ignoreDirection, List<String> ignoredProperties) {
        return predicted != null && desired != null
                && predicted.getBlock() == desired.getBlock()
                && !isStructuralWorkMismatch(predicted, desired, ignoreDirection, ignoredProperties);
    }

    static boolean itemCouldPotentiallyPlace(BlockState itemState, BlockState desired) {
        return itemState != null && desired != null && itemState.getBlock() == desired.getBlock();
    }

    private static boolean containsBlockState(Collection<BlockState> states, BlockState state) {
        for (BlockState testee : states) {
            if (itemCouldPotentiallyPlace(testee, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        if (current.getBlock() instanceof LiquidBlock && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return sameBlockstate(current, desired);
    }

    public class BuilderCalculationContext extends CalculationContext {

        private final List<BlockState> placeable;
        private final ISchematic schematic;
        private final int originX;
        private final int originY;
        private final int originZ;

        public BuilderCalculationContext() {
            super(BuilderProcess.this.baritone, true); // wew lad
            this.placeable = approxPlaceable(9);
            this.schematic = BuilderProcess.this.schematic;
            this.originX = origin.getX();
            this.originY = origin.getY();
            this.originZ = origin.getZ();

            this.jumpPenalty += 10;
            this.backtrackCostFavoringCoefficient = 1;
        }

        private BlockState getSchematic(int x, int y, int z, BlockState current) {
            if (schematic.inSchematic(x - originX, y - originY, z - originZ, current)) {
                return schematic.desiredState(x - originX, y - originY, z - originZ, current, BuilderProcess.this.approxPlaceable);
            } else {
                return null;
            }
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            if (isPossiblyProtected(x, y, z) || !worldBorder.canPlaceAt(x, z)) { // make calculation fail properly if we can't build
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                // TODO this can return true even when allowPlace is off.... is that an issue?
                if (sch.getBlock() instanceof AirBlock) {
                    // we want this to be air, but they're asking if they can place here
                    // this won't be a schematic block, this will be a throwaway
                    return placeBlockCost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // we're going to have to break it eventually
                }
                if (placeable.contains(sch)) {
                    return 0; // thats right we gonna make it FREE to place a block where it should go in a structure
                    // no place block penalty at all 😎
                    // i'm such an idiot that i just tried to copy and paste the epic gamer moment emoji too
                    // get added to unicode when?
                }
                if (!hasThrowaway) {
                    return COST_INF;
                }
                // we want it to be something that we don't have
                // even more of a pain to place something wrong
                return placeBlockCost * 1.5 * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value;
            } else {
                if (hasThrowaway) {
                    return placeBlockCost;
                } else {
                    return COST_INF;
                }
            }
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            if ((!allowBreak && !allowBreakAnyway.contains(current.getBlock())) || isPossiblyProtected(x, y, z)) {
                return COST_INF;
            }
            BlockState sch = getSchematic(x, y, z, current);
            if (sch != null) {
                if (sch.getBlock() instanceof AirBlock) {
                    // it should be air
                    // regardless of current contents, we can break it
                    return 1;
                }
                // it should be a real block
                // is it already that block?
                if (valid(bsi.get0(x, y, z), sch, false)) {
                    return Baritone.settings().breakCorrectBlockPenaltyMultiplier.value;
                } else {
                    // can break if it's wrong
                    // would be great to return less than 1 here, but that would actually make the cost calculation messed up
                    // since we're breaking a block, if we underestimate the cost, then it'll fail when it really takes the correct amount of time
                    return 1;

                }
                // TODO do blocks in render distace only?
                // TODO allow breaking blocks that we have a tool to harvest and immediately place back?
            } else {
                return 1; // why not lol
            }
        }
    }
}
