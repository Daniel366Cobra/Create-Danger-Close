package io.github.daniel366cobra.createdangerclose.foundation.sensor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * The angles a servo may turn what it drives through, set on its protractor
 * dial: an arc from {@code start}, {@code length} degrees on. Dial angles count
 * from the assembled position: for a mount's pitch the barrel's elevation (up
 * positive); for a mount's yaw and for a swivel bearing, the turn from the way it
 * was assembled, clockwise as seen looking at the bearing's face (from above, for
 * a mount or an upright turret).
 *
 * <p>A full circle ({@code length} 360) does not limit a turn at all; nor does
 * -90..90 for pitch.
 */
public record ServoLimits(float start, float length) {

    public static final ServoLimits FULL_TURN = new ServoLimits(-180, 360);
    public static final ServoLimits FULL_ELEVATION = new ServoLimits(-90, 180);

    public float end() {
        return start + length;
    }

    /** Whether this arc leaves out any of a full turn. */
    public boolean limitsTurn() {
        return length < 360;
    }

    /** How far round the arc a dial angle lies from its start, 0..360. */
    public float along(float dial) {
        float along = (dial - start) % 360;
        return along < 0 ? along + 360 : along;
    }

    /** Whether a turn may reach this dial angle. */
    public boolean containsTurn(double dial) {
        return !limitsTurn() || along((float) dial) <= length + 1e-3f;
    }

    /** Whether the barrel may elevate to this angle. */
    public boolean containsElevation(double elevation) {
        return elevation >= start - 1e-3f && elevation <= end() + 1e-3f;
    }

    /**
     * How far to turn, degrees, from one dial angle towards another, staying
     * inside the arc: never through the part left out (a turret swings the long
     * way round rather than through its own bridge), and stopping at the nearer
     * end if the target lies outside it.
     */
    public float turnWithin(float from, float to) {
        if (!limitsTurn())
            return Mth.wrapDegrees(to - from);
        float target = along(to);
        if (target > length)
            target = (target - length) < (360 - target) ? length : 0;
        float now = along(from);
        if (now > length) // outside the arc (the limits changed): back to its nearer end
            return Mth.wrapDegrees(start + ((now - length) < (360 - now) ? length : 0) - from);
        return target - now;
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Start", start);
        tag.putFloat("Length", length);
        return tag;
    }

    public static ServoLimits read(CompoundTag tag, ServoLimits fallback) {
        if (!tag.contains("Length"))
            return fallback;
        return new ServoLimits(tag.getFloat("Start"), Mth.clamp(tag.getFloat("Length"), 1, 360));
    }
}
