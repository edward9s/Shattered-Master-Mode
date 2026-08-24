package com.spd.mod.items;

/**
 * Legacy save compatibility for builds that serialized the old class name.
 * New code should use {@link ModRestorativeBrew}.
 */
@Deprecated
public class RestorativeBrew extends ModRestorativeBrew {
    public RestorativeBrew() {
        super();
    }
}
