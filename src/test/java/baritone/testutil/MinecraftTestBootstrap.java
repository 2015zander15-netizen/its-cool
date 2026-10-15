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

package baritone.testutil;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Ensures Minecraft's static registries are bootstrapped once before unit tests inspect block states.
 */
public final class MinecraftTestBootstrap {

    private static boolean bootstrapped;

    private MinecraftTestBootstrap() {
    }

    public static synchronized void ensureInitialized() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }
}
