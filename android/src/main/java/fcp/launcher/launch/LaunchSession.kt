// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch

import fcp.core.launch.RunningGame

/** The one Minecraft process this app has started. */
object LaunchSession {
    @Volatile
    var current: RunningGame? = null
        private set

    @Synchronized
    fun replace(next: RunningGame) {
        val previous = current
        current = next
        if (previous != null && previous !== next && previous.process.isAlive) {
            previous.process.destroy()
        }
    }

    @Synchronized
    fun clearIfCurrent(game: RunningGame) {
        if (current === game) current = null
    }
}
