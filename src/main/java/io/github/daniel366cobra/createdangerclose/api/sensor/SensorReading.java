package io.github.daniel366cobra.createdangerclose.api.sensor;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

public sealed interface SensorReading {
    Kind kind();
    CompoundTag serializeNBT(HolderLookup.Provider registries);

    enum Kind {
        GIMBAL, NAV_HEADING, NAV_TARGET, VELOCITY, ALTITUDE, RANGEFINDER, NONE
    }

    interface Provider {
        SensorReading getSensorReading();
    }

    /**
     * Gimbal sensor tilt, as the sensor itself reports it: the angles of world
     * "down" in the sub-level's X and Z planes. These are sub-level-frame
     * values regardless of how the gimbal block is placed.
     */
    record Gimbal(double xAngleDeg, double zAngleDeg) implements SensorReading {
        @Override public Kind kind() { return Kind.GIMBAL; }

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("x", xAngleDeg);
            tag.putDouble("z", zAngleDeg);
            return tag;
        }

        static Gimbal fromNBT(CompoundTag tag) {
            return new Gimbal(tag.getDouble("x"), tag.getDouble("z"));
        }
    }

    record NavHeading(double headingDeg) implements SensorReading {
        @Override public Kind kind() { return Kind.NAV_HEADING; }

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("heading", headingDeg);
            return tag;
        }

        static NavHeading fromNBT(CompoundTag tag) {
            return new NavHeading(tag.getDouble("heading"));
        }
    }

    record NavTarget(Vec3 worldPos, double bearingDeg) implements SensorReading {
        @Override public Kind kind() { return Kind.NAV_TARGET; }

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("px", worldPos.x);
            tag.putDouble("py", worldPos.y);
            tag.putDouble("pz", worldPos.z);
            tag.putDouble("bearing", bearingDeg);
            return tag;
        }

        static NavTarget fromNBT(CompoundTag tag) {
            return new NavTarget(
                    new Vec3(tag.getDouble("px"), tag.getDouble("py"), tag.getDouble("pz")),
                    tag.getDouble("bearing"));
        }
    }

    record Velocity(Vector3d localVelocity, Vector3d normal) implements SensorReading {
        @Override public Kind kind() { return Kind.VELOCITY; }

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("vx", localVelocity.x);
            tag.putDouble("vy", localVelocity.y);
            tag.putDouble("vz", localVelocity.z);
            tag.putDouble("nx", normal.x);
            tag.putDouble("ny", normal.y);
            tag.putDouble("nz", normal.z);
            return tag;
        }

        static Velocity fromNBT(CompoundTag tag) {
            return new Velocity(
                    new Vector3d(tag.getDouble("vx"), tag.getDouble("vy"), tag.getDouble("vz")),
                    new Vector3d(tag.getDouble("nx"), tag.getDouble("ny"), tag.getDouble("nz")));
        }
    }

    record Altitude(double worldHeight) implements SensorReading {
        @Override public Kind kind() { return Kind.ALTITUDE; }

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("h", worldHeight);
            return tag;
        }

        static Altitude fromNBT(CompoundTag tag) {
            return new Altitude(tag.getDouble("h"));
        }
    }

    /**
     * A target tracked by a rangefinder, world space.
     *
     * @param hitWorldPos    tracked target position as of {@code sampleTick}
     * @param targetVelocity tracked target velocity, blocks per tick; null until tracked
     * @param sampleTick     game time of the latest reading, for freshness and extrapolation
     * @param rangeSigma     1σ range error of the latest reading, blocks
     */
    record Rangefinder(
            double bearingDeg,
            double elevationDeg,
            double rangeMeters,
            boolean hitSubLevel,
            @Nullable Vector3d targetVelocity,
            Vec3 hitWorldPos,
            long sampleTick,
            double rangeSigma
    ) implements SensorReading {
        @Override public Kind kind() { return Kind.RANGEFINDER; }

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("bearing", bearingDeg);
            tag.putDouble("elevation", elevationDeg);
            tag.putDouble("range", rangeMeters);
            tag.putBoolean("hitSubLevel", hitSubLevel);
            if (targetVelocity != null) {
                tag.putDouble("tvx", targetVelocity.x);
                tag.putDouble("tvy", targetVelocity.y);
                tag.putDouble("tvz", targetVelocity.z);
            }
            tag.putDouble("hx", hitWorldPos.x);
            tag.putDouble("hy", hitWorldPos.y);
            tag.putDouble("hz", hitWorldPos.z);
            tag.putLong("tick", sampleTick);
            tag.putDouble("sigma", rangeSigma);
            return tag;
        }

        static Rangefinder fromNBT(CompoundTag tag) {
            Vector3d tv = tag.contains("tvx")
                    ? new Vector3d(tag.getDouble("tvx"), tag.getDouble("tvy"), tag.getDouble("tvz"))
                    : null;
            return new Rangefinder(
                    tag.getDouble("bearing"),
                    tag.getDouble("elevation"),
                    tag.getDouble("range"),
                    tag.getBoolean("hitSubLevel"),
                    tv,
                    new Vec3(tag.getDouble("hx"), tag.getDouble("hy"), tag.getDouble("hz")),
                    tag.getLong("tick"),
                    tag.getDouble("sigma"));
        }
    }

    record None() implements SensorReading {
        @Override public Kind kind() { return Kind.NONE; }
        @Override public CompoundTag serializeNBT(HolderLookup.Provider registries) { return new CompoundTag(); }
    }

    // --- Static helpers ---

    static CompoundTag write(SensorReading reading, HolderLookup.Provider registries) {
        CompoundTag tag = reading.serializeNBT(registries);
        tag.putInt("kind", reading.kind().ordinal());
        return tag;
    }

    static SensorReading read(CompoundTag tag, HolderLookup.Provider registries) {
        int ordinal = tag.getInt("kind");
        Kind[] kinds = Kind.values();
        if (ordinal < 0 || ordinal >= kinds.length) return new None();
        return switch (kinds[ordinal]) {
            case GIMBAL      -> Gimbal.fromNBT(tag);
            case NAV_HEADING -> NavHeading.fromNBT(tag);
            case NAV_TARGET  -> NavTarget.fromNBT(tag);
            case VELOCITY    -> Velocity.fromNBT(tag);
            case ALTITUDE    -> Altitude.fromNBT(tag);
            case RANGEFINDER -> Rangefinder.fromNBT(tag);
            case NONE        -> new None();
        };
    }
}