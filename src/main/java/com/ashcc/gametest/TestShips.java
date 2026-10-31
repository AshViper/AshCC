package com.ashcc.gametest;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Removes Sable ships a test made. Ships are not part of the test area, so they outlive it and can turn up in the next
 * batch's tests (a seeker or radar then finds them). Call only from a test that already knows Sable is loaded.
 */
final class TestShips {
    private TestShips() {
    }

    static void remove(GameTestHelper helper, Object... ships) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(helper.getLevel());
        for (Object o : ships) {
            if (o instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel s && !s.isRemoved()) {
                container.removeSubLevel(s, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
            }
        }
    }
}
