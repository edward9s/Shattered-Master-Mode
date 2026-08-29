package com.spd.mod.journal;

/**
 * Compatibility tombstone for stale overlay worktrees created before Total
 * replaced the old Parry/Riposte mode selector.
 *
 * The local build script overlays files without deleting removed Java sources,
 * so keeping this inert class ensures an older ModParryRiposteSelector.java is
 * overwritten instead of being left behind to reference the removed Mode API.
 */
@Deprecated
public final class ModParryRiposteSelector {

    private ModParryRiposteSelector() {
    }
}
