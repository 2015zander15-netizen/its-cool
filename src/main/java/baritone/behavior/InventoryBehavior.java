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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.Helper;
import baritone.utils.ToolSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.Predicate;

public final class InventoryBehavior extends Behavior implements Helper {

    int ticksSinceLastInventoryMove;
    int[] lastTickRequestedMove; // not everything asks every tick, so remember the request while coming to a halt

    public InventoryBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (!Baritone.settings().allowInventory.value) {
            return;
        }
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            // we have a crafting table or a chest or something open
            return;
        }
        ticksSinceLastInventoryMove++;

        int[] nextMove;
        if (lastTickRequestedMove != null) {
            nextMove = chooseNextInventoryMove(lastTickRequestedMove, -1, -1);
        } else {
            nextMove = chooseNextInventoryMove(
                    null,
                    firstValidThrowaway(),
                    bestToolAgainst(Blocks.STONE, ItemTags.PICKAXES)
            );
        }
        if (nextMove != null) {
            // A deferred swap has priority. Otherwise an automatic tool/throwaway request could overwrite
            // the queued source/destination while the cooldown or stationary guard is still blocking it.
            logDebug("Processing inventory move " + nextMove[0] + " -> " + nextMove[1]);
            requestSwapWithHotBar(nextMove[0], nextMove[1]);
            return; // one move per tick; preserve any queued request until it succeeds or is invalidated
        }
    }

    public boolean attemptToPutOnHotbar(int inMainInvy, Predicate<Integer> disallowedHotbar) {
        OptionalInt destination = getTempHotbarSlot(disallowedHotbar);
        if (destination.isPresent()) {
            if (!requestSwapWithHotBar(inMainInvy, destination.getAsInt())) {
                return false;
            }
        }
        return true;
    }

    public OptionalInt getTempHotbarSlot(Predicate<Integer> disallowedHotbar) {
        // we're using 0 and 8 for pickaxe and throwaway
        boolean[] emptySlots = new boolean[8];
        for (int i = 1; i < 8; i++) {
            emptySlots[i] = ctx.player().getInventory().getNonEquipmentItems().get(i).isEmpty();
        }
        ArrayList<Integer> candidates = getTempHotbarCandidates(emptySlots, disallowedHotbar);
        if (candidates.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(candidates.get(new Random().nextInt(candidates.size())));
    }

    static ArrayList<Integer> getTempHotbarCandidates(boolean[] emptySlots, Predicate<Integer> disallowedHotbar) {
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < 8; i++) {
            if (i < emptySlots.length && emptySlots[i] && !disallowedHotbar.test(i)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 1; i < 8; i++) {
                if (!disallowedHotbar.test(i)) {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) {
            // Every temporary slot currently contains a block needed by this build.
            // A SWAP is reversible: the displaced stack goes into the main inventory and
            // Baritone can cycle it back onto the hotbar when that block is needed again.
            for (int i = 1; i < 8; i++) {
                candidates.add(i);
            }
        }
        return candidates;
    }

    static int[] chooseNextInventoryMove(int[] pendingMove, int throwawaySlot, int pickSlot) {
        if (pendingMove != null && pendingMove.length >= 2) {
            return new int[]{pendingMove[0], pendingMove[1]};
        }
        if (throwawaySlot >= 9) {
            return new int[]{throwawaySlot, 8};
        }
        if (pickSlot >= 9) {
            return new int[]{pickSlot, 0};
        }
        return null;
    }

    static boolean isValidSwapRequest(int sourceSlot, int hotbarSlot, int inventorySize, boolean sourceOccupied) {
        return sourceSlot >= 0 && sourceSlot < inventorySize
                && hotbarSlot >= 0 && hotbarSlot < 9
                && sourceOccupied;
    }

    private boolean requestSwapWithHotBar(int inInventory, int inHotbar) {
        lastTickRequestedMove = new int[]{inInventory, inHotbar};
        NonNullList<ItemStack> inventory = ctx.player().getInventory().getNonEquipmentItems();
        boolean sourceOccupied = inInventory >= 0 && inInventory < inventory.size()
                && !inventory.get(inInventory).isEmpty();
        if (!isValidSwapRequest(inInventory, inHotbar, inventory.size(), sourceOccupied)) {
            lastTickRequestedMove = null;
            logDebug("Dropping stale/invalid inventory swap request for source slot " + inInventory
                    + " and hotbar slot " + inHotbar);
            return false;
        }
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            logDebug("Inventory swap requested while a non-inventory container is open; waiting until it closes");
            return false;
        }
        if (ticksSinceLastInventoryMove < Baritone.settings().ticksBetweenInventoryMoves.value) {
            logDebug("Inventory move requested but delaying " + ticksSinceLastInventoryMove + " " + Baritone.settings().ticksBetweenInventoryMoves.value);
            return false;
        }
        if (Baritone.settings().inventoryMoveOnlyIfStationary.value && !baritone.getInventoryPauserProcess().stationaryForInventoryMove()) {
            logDebug("Inventory move requested but delaying until stationary");
            return false;
        }
        ItemStack requestedStack = inventory.get(inInventory).copy();
        ctx.playerController().windowClick(
                ctx.player().inventoryMenu.containerId,
                inInventory < 9 ? inInventory + 36 : inInventory,
                inHotbar,
                ClickType.SWAP,
                ctx.player()
        );
        ticksSinceLastInventoryMove = 0;
        lastTickRequestedMove = null;

        // A valid click request is not enough: the item must actually be in the requested
        // destination before callers select that hotbar slot and attempt to place it.
        if (!isExpectedStackInSlot(requestedStack, inventory.get(inHotbar))) {
            logDebug("Inventory swap did not put the requested stack in hotbar slot " + inHotbar
                    + "; refusing to select that slot");
            return false;
        }
        return true;
    }

    static boolean isExpectedStackInSlot(ItemStack expected, ItemStack actual) {
        return expected != null && actual != null
                && !expected.isEmpty() && !actual.isEmpty()
                && expected.getCount() == actual.getCount()
                && expected.getItem() == actual.getItem()
                && expected.getDamageValue() == actual.getDamageValue()
                && expected.getComponents().equals(actual.getComponents());
    }

    private int firstValidThrowaway() { // TODO offhand idk
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            if (Baritone.settings().acceptableThrowawayItems.value.contains(invy.get(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    private int bestToolAgainst(Block against, TagKey<Item> toolTag) {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        int bestInd = -1;
        double bestSpeed = -1;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack stack = invy.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (Baritone.settings().itemSaver.value && (stack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= stack.getMaxDamage() && stack.getMaxDamage() > 1) {
                continue;
            }
            if (stack.is(toolTag)) {
                double speed = ToolSet.calculateSpeedVsBlock(stack, against.defaultBlockState()); // takes into account enchants
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestInd = i;
                }
            }
        }
        return bestInd;
    }

    public boolean hasGenericThrowaway() {
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(false, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    private boolean itemCouldPlaceState(ItemStack stack, BlockState desired) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
            return false;
        }
        try {
            BlockState predicted = ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(
                    new BlockPlaceContext(new UseOnContext(
                            ctx.world(),
                            ctx.player(),
                            InteractionHand.MAIN_HAND,
                            stack,
                            new BlockHitResult(
                                    new Vec3(ctx.player().position().x, ctx.player().position().y, ctx.player().position().z),
                                    Direction.UP,
                                    ctx.playerFeet(),
                                    false
                            )
                    ) {})
            );
            return predicted != null && desired.equals(predicted);
        } catch (RuntimeException exception) {
            // Prediction is advisory: if a modded item throws, let the block-identity fallback below try it.
            logDebug("Unable to predict throwaway placement for " + stack.getItem() + ": " + exception);
            return false;
        }
    }

    public boolean selectThrowawayForLocation(boolean select, int x, int y, int z) {
        BlockState maybe = baritone.getBuilderProcess().placeAt(x, y, z, baritone.bsi.get0(x, y, z));
        if (maybe != null && throwaway(select, stack -> itemCouldPlaceState(stack, maybe))) {
            return true; // gotem
        }
        if (maybe != null && throwaway(select, stack -> stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock().equals(maybe.getBlock()))) {
            return true;
        }
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (throwaway(select, stack -> item.equals(stack.getItem()))) {
                return true;
            }
        }
        return false;
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired) {
        return throwaway(select, desired, Baritone.settings().allowInventory.value);
    }

    public boolean throwaway(boolean select, Predicate<? super ItemStack> desired, boolean allowInventory) {
        LocalPlayer p = ctx.player();
        NonNullList<ItemStack> inv = p.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.get(i);
            // this usage of settings() is okay because it's only called once during pathing
            // (while creating the CalculationContext at the very beginning)
            // and then it's called during execution
            // since this function is never called during cost calculation, we don't need to migrate
            // acceptableThrowawayItems to the CalculationContext
            if (desired.test(item)) {
                if (select) {
                    p.getInventory().setSelectedSlot(i);
                }
                return true;
            }
        }
        if (desired.test(p.getOffhandItem())) {
            // main hand takes precedence over off hand
            // that means that if we have block A selected in main hand and block B in off hand, right clicking places block B
            // we've already checked above ^ and the main hand can't possible have an acceptablethrowawayitem
            // so we need to select in the main hand something that doesn't right click
            // so not a shovel, not a hoe, not a block, etc
            for (int i = 0; i < 9; i++) {
                ItemStack item = inv.get(i);
                if (item.isEmpty() || item.is(ItemTags.PICKAXES)) {
                    if (select) {
                        p.getInventory().setSelectedSlot(i);
                    }
                    return true;
                }
            }
        }

        if (allowInventory) {
            for (int i = 9; i < 36; i++) {
                if (desired.test(inv.get(i))) {
                    if (!select) {
                        return true;
                    }
                    // Do not select the destination until the server/client inventory swap has
                    // actually been issued. A delayed swap leaves the old slot contents in place.
                    if (!requestSwapWithHotBar(i, 7)) {
                        return false;
                    }
                    p.getInventory().setSelectedSlot(7);
                    return true;
                }
            }
        }

        return false;
    }
}
