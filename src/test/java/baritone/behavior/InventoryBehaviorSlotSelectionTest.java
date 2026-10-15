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

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class InventoryBehaviorSlotSelectionTest {

    @Test
    public void hotbarSelectionRequiresTheExpectedStackToBePresent() {
        ItemStack expected = new ItemStack(Items.STONE, 32);
        ItemStack exact = new ItemStack(Items.STONE, 32);
        ItemStack wrongCount = new ItemStack(Items.STONE, 31);
        ItemStack wrongItem = new ItemStack(Items.DIRT, 32);
        ItemStack namedStone = new ItemStack(Items.STONE, 32);
        namedStone.set(DataComponents.CUSTOM_NAME, Component.literal("not the same stack"));
        ItemStack intactPickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack damagedPickaxe = intactPickaxe.copy();
        damagedPickaxe.setDamageValue(1);

        assertTrue(InventoryBehavior.isExpectedStackInSlot(expected, exact));
        assertFalse(InventoryBehavior.isExpectedStackInSlot(expected, wrongCount));
        assertFalse(InventoryBehavior.isExpectedStackInSlot(expected, wrongItem));
        assertFalse(InventoryBehavior.isExpectedStackInSlot(expected, namedStone));
        assertFalse(InventoryBehavior.isExpectedStackInSlot(intactPickaxe, damagedPickaxe));
        assertFalse(InventoryBehavior.isExpectedStackInSlot(expected, ItemStack.EMPTY));
        assertFalse(InventoryBehavior.isExpectedStackInSlot(ItemStack.EMPTY, exact));
        assertFalse(InventoryBehavior.isExpectedStackInSlot(null, exact));
    }

    @Test
    public void pendingInventoryMoveHasPriorityOverAutomaticMoves() {
        int[] pending = {12, 5};

        assertArrayEquals(pending, InventoryBehavior.chooseNextInventoryMove(pending, 20, 15));
        assertArrayEquals(pending, InventoryBehavior.chooseNextInventoryMove(pending, -1, -1));
    }

    @Test
    public void throwawayMoveHasPriorityOverToolMove() {
        assertArrayEquals(new int[]{17, 8}, InventoryBehavior.chooseNextInventoryMove(null, 17, 12));
    }

    @Test
    public void toolMoveIsSelectedWhenNoThrowawayNeedsMoving() {
        assertArrayEquals(new int[]{13, 0}, InventoryBehavior.chooseNextInventoryMove(null, -1, 13));
    }

    @Test
    public void noAutomaticInventoryMoveIsReturnedWhenNotNeeded() {
        assertNull(InventoryBehavior.chooseNextInventoryMove(null, -1, -1));
        assertNotNull(InventoryBehavior.chooseNextInventoryMove(new int[]{9, 3}, -1, -1));
    }

    @Test
    public void swapRequestRequiresValidSlotsAndAStillOccupiedSource() {
        assertTrue(InventoryBehavior.isValidSwapRequest(9, 7, 36, true));
        assertTrue(InventoryBehavior.isValidSwapRequest(35, 1, 36, true));
        assertFalse(InventoryBehavior.isValidSwapRequest(-1, 7, 36, true));
        assertFalse(InventoryBehavior.isValidSwapRequest(36, 7, 36, true));
        assertFalse(InventoryBehavior.isValidSwapRequest(9, 9, 36, true));
        assertFalse(InventoryBehavior.isValidSwapRequest(9, 7, 36, false));
    }

    @Test
    public void prefersAnEmptySlotThatIsNotNeededForAnotherBlock() {
        boolean[] emptySlots = new boolean[8];
        emptySlots[3] = true;
        emptySlots[4] = true;

        ArrayList<Integer> candidates =
                InventoryBehavior.getTempHotbarCandidates(emptySlots, slot -> slot == 3);

        assertEquals(Arrays.asList(4), candidates);
    }

    @Test
    public void usesAnOccupiedButAvailableSlotBeforeReplacingANeededSlot() {
        boolean[] emptySlots = new boolean[8];

        ArrayList<Integer> candidates =
                InventoryBehavior.getTempHotbarCandidates(emptySlots, slot -> slot != 7);

        assertEquals(Arrays.asList(7), candidates);
    }

    @Test
    public void fallsBackToReversibleSwapWhenEveryTemporarySlotIsNeeded() {
        boolean[] emptySlots = new boolean[8];

        ArrayList<Integer> candidates =
                InventoryBehavior.getTempHotbarCandidates(emptySlots, slot -> true);

        assertEquals(7, candidates.size());
        for (int slot = 1; slot < 8; slot++) {
            assertTrue(candidates.contains(slot));
        }
        assertTrue(candidates.stream().noneMatch(slot -> slot == 0 || slot == 8));
    }
}
