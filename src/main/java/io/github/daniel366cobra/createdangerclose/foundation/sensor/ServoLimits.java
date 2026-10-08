package io.github.daniel366cobra.createdangerclose.foundation.sensor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * The arc set on a servo's dial: from {@code start}, {@code length} degrees on. Dial angles count from
 * the assembled position: elevation (up positive) for pitch; clockwise seen facing the bearing for yaw
 * and swivels. 360 (or -90..90 for pitch) does not limit at all.
 */
public record ServoLimits(float start, float length) {

    public static final ServoLimits FULL_TURN = new ServoLimits(-180, 360);
    public static final ServoLimits FULL_ELEVATION = new ServoLimits(-90, 180);

    public float end() {
        return start + length;
    }

    /** Leaves out any of a full turn. */
    public boolean limitsTurn() {
        return length < 360;
    }

    /** 0..360 from the start. */
    public float along(float dial) {
        float along = (dial - start) % 360;
        return along < 0 ? along + 360 : along;
    }

    public boolean containsTurn(double dial) {
        return !limitsTurn() || along((float) dial) <= length + 1e-3f;
    }

    public boolean containsElevation(double elevation) {
        return elevation >= start - 1e-3f && elevation <= end() + 1e-3f;
    }

    /** Degrees, never through the excluded part (the long way round), stopping at the nearer end if outside. */
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