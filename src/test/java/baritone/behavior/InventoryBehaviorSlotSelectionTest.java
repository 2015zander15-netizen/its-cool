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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InventoryBehaviorSlotSelectionTest {

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
