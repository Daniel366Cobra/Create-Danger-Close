package io.github.daniel366cobra.createdangerclose.foundation.sensor;

/** What a cannon servo drives, as found on its cannon network. */
public enum ServoAxis {
    /** A cannon mount's pitch interface: the barrel's elevation. */
    PITCH,
    /** A cannon mount's yaw interface: the mount's traverse. */
    YAW,
    NONE,
    INVALID,
    /** A swivel bearing, facing any way: it turns what its plate carries (a turret, a cradle) and the guns on it. */
    SWIVEL,
    /** A gun on a fixed cannon mount, read by the network interface facing it: no drive of its own. */
    FIXED_GUN
}