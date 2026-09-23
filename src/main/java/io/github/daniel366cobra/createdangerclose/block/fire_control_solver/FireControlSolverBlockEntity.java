package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.lang.ref.WeakReference;
import java.util.List;

public class FireControlSolverBlockEntity extends KineticBlockEntity
        implements IHaveGoggleInformation {

    public static final float MIN_OPERATING_RPM = 32.0f;

    // --- Fused own-ship state (persisted scalars) ---
    private boolean hasOrientation = false;
    private double shipPitchDeg = 0;
    private double shipYawDeg = 0;
    private double shipRollDeg = 0;

    private boolean hasVelocity = false;
    private double shipVelX = 0;
    private double shipVelY = 0;
    private double shipVelZ = 0;

    private boolean hasAltitude = false;
    private double shipAltitude = 0;

    private boolean hasTarget = false;
    private double targetX = 0;
    private double targetY = 0;
    private double targetZ = 0;

    // --- Server-side intermediates for the future ballistic solve ---
    @Nullable private transient Quaterniond shipTiltQuat;
    @Nullable private transient Quaterniond shipHeadingQuat;
    @Nullable private transient Vector3d shipVelocityVec;
    @Nullable private transient Vector3d targetPositionVec;

    // --- Sensor counts for the tooltip ---
    private int gimbalCount = 0;
    private int navHeadingCount = 0;
    private int navTargetCount = 0;
    private int velocityCount = 0;
    private int altitudeCount = 0;

    private boolean isPowered = false;

    private WeakReference<SubLevel> subLevelReference;
    private WeakReference<KineticNetwork> battleNetworkReference;

    public FireControlSolverBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.subLevelReference = new WeakReference<>(null);
        this.battleNetworkReference = new WeakReference<>(null);
    }

    @Override
    public void initialize() {
        super.initialize();
        this.subLevelReference = new WeakReference<>(
                Sable.HELPER.getContaining(this.getLevel(), this.worldPosition));
        this.battleNetworkReference = new WeakReference<>(
                com.simibubi.create.Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this));
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide()) return;

        float speed = Math.abs(getSpeed());
        isPowered = speed >= MIN_OPERATING_RPM;

        if (!isPowered) {
            invalidateData();
            notifyUpdate();
        }

        int pollInterval = (int) Math.max(1, 640f / speed);
        if (level.getGameTime() % pollInterval == 0) {
            pollNetwork();
            notifyUpdate();
        }
    }

    private void invalidateData() {
        hasOrientation = false;
        hasVelocity = false;
        hasAltitude = false;
        hasTarget = false;
        shipPitchDeg = shipYawDeg = shipRollDeg = 0;
        shipVelX = shipVelY = shipVelZ = 0;
        shipAltitude = 0;
        targetX = targetY = targetZ = 0;
        shipTiltQuat = null;
        shipHeadingQuat = null;
        shipVelocityVec = null;
        targetPositionVec = null;
        gimbalCount = navHeadingCount = navTargetCount = velocityCount = altitudeCount = 0;
    }

    void pollNetwork() {
        invalidateData();

        KineticNetwork network = battleNetworkReference.get();
        if (network == null) return;

        Vector3d velocitySum = new Vector3d();
        int velocityContributions = 0;

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member == this) continue;
            if (!(member instanceof SensorReading.Provider provider)) continue;

            SensorReading reading = provider.getSensorReading();
            switch (reading) {
                case SensorReading.Gimbal g -> {
                    gimbalCount++;
                    if (shipTiltQuat == null) {
                        shipTiltQuat = new Quaterniond(g.tilt());
                    }
                }
                case SensorReading.NavHeading n -> {
                    navHeadingCount++;
                    if (shipHeadingQuat == null) {
                        shipHeadingQuat = new Quaterniond(n.yaw());
                    }
                }
                case SensorReading.NavTarget t -> {
                    navTargetCount++;
                    if (targetPositionVec == null) {
                        targetPositionVec = new Vector3d(
                                t.worldPos().x, t.worldPos().y, t.worldPos().z);
                    }
                }
                case SensorReading.Velocity v -> {
                    velocityCount++;
                    velocitySum.add(v.localVelocity());
                    velocityContributions++;
                }
                case SensorReading.Altitude a -> {
                    altitudeCount++;
                    if (!hasAltitude) {
                        shipAltitude = a.worldHeight();
                        hasAltitude = true;
                    }
                }
                case SensorReading.Rangefinder ignored -> { }
                case SensorReading.None ignored -> { }
            }
        }

        if (velocityContributions > 0) {
            shipVelocityVec = velocitySum;
            shipVelX = velocitySum.x;
            shipVelY = velocitySum.y;
            shipVelZ = velocitySum.z;
            hasVelocity = true;
        }

        // Orientation
        if (shipTiltQuat != null) {
            double[] euler = eulerFromQuat(shipTiltQuat);
            shipPitchDeg = Math.toDegrees(euler[0]);
            shipRollDeg = Math.toDegrees(euler[2]);
            hasOrientation = true;
        }
        if (shipHeadingQuat != null) {
            shipYawDeg = Math.toDegrees(yawFromQuat(shipHeadingQuat));
            hasOrientation = true;
        }
        // If only one of tilt/heading is present, hasOrientation is still true
        // but the tooltip will need to distinguish. We'll use the counts for that.

        // Target
        if (targetPositionVec != null) {
            targetX = targetPositionVec.x;
            targetY = targetPositionVec.y;
            targetZ = targetPositionVec.z;
            hasTarget = true;
        }
    }

    // --- Accessors for other blocks (fire control panel, map table, etc.) ---

    public boolean hasOrientation() { return hasOrientation; }
    public boolean hasVelocity() { return hasVelocity; }
    public boolean hasAltitude() { return hasAltitude; }
    public boolean hasTarget() { return hasTarget; }

    public double getShipPitchDeg() { return shipPitchDeg; }
    public double getShipYawDeg() { return shipYawDeg; }
    public double getShipRollDeg() { return shipRollDeg; }

    public double getShipVelX() { return shipVelX; }
    public double getShipVelY() { return shipVelY; }
    public double getShipVelZ() { return shipVelZ; }

    public double getShipAltitude() { return shipAltitude; }

    public double getTargetX() { return targetX; }
    public double getTargetY() { return targetY; }
    public double getTargetZ() { return targetZ; }

    public boolean isPowered() { return isPowered; }

    // --- NBT ---
    // The fused state is re-derived from the network each poll, so we don't
    // need to persist it. We do persist the counters and power flag so the
    // client has something to render before the first poll.

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        this.isPowered = compound.getBoolean("IsPowered");

        this.hasOrientation = compound.getBoolean("HasOrientation");
        this.shipPitchDeg = compound.getDouble("ShipPitch");
        this.shipYawDeg = compound.getDouble("ShipYaw");
        this.shipRollDeg = compound.getDouble("ShipRoll");

        this.hasVelocity = compound.getBoolean("HasVelocity");
        this.shipVelX = compound.getDouble("VelX");
        this.shipVelY = compound.getDouble("VelY");
        this.shipVelZ = compound.getDouble("VelZ");

        this.hasAltitude = compound.getBoolean("HasAltitude");
        this.shipAltitude = compound.getDouble("Altitude");

        this.hasTarget = compound.getBoolean("HasTarget");
        this.targetX = compound.getDouble("TargetX");
        this.targetY = compound.getDouble("TargetY");
        this.targetZ = compound.getDouble("TargetZ");

        this.gimbalCount = compound.getInt("GimbalCount");
        this.navHeadingCount = compound.getInt("NavHeadingCount");
        this.navTargetCount = compound.getInt("NavTargetCount");
        this.velocityCount = compound.getInt("VelocityCount");
        this.altitudeCount = compound.getInt("AltitudeCount");

        // Transient quaternions/vectors are not persisted. On the server they'll
        // be recomputed on the next poll; on the client they stay null.
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putBoolean("IsPowered", isPowered);

        compound.putBoolean("HasOrientation", hasOrientation);
        compound.putDouble("ShipPitch", shipPitchDeg);
        compound.putDouble("ShipYaw", shipYawDeg);
        compound.putDouble("ShipRoll", shipRollDeg);

        compound.putBoolean("HasVelocity", hasVelocity);
        compound.putDouble("VelX", shipVelX);
        compound.putDouble("VelY", shipVelY);
        compound.putDouble("VelZ", shipVelZ);

        compound.putBoolean("HasAltitude", hasAltitude);
        compound.putDouble("Altitude", shipAltitude);

        compound.putBoolean("HasTarget", hasTarget);
        compound.putDouble("TargetX", targetX);
        compound.putDouble("TargetY", targetY);
        compound.putDouble("TargetZ", targetZ);

        compound.putInt("GimbalCount", gimbalCount);
        compound.putInt("NavHeadingCount", navHeadingCount);
        compound.putInt("NavTargetCount", navTargetCount);
        compound.putInt("VelocityCount", velocityCount);
        compound.putInt("AltitudeCount", altitudeCount);
    }

    // --- TOOLTIP ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.fire_control_solver.title"))
                .forGoggles(tooltip);

        if (!isPowered) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.error.network_too_slow"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            return true;
        }

        // --- Orientation ---
        boolean hasGimbal = gimbalCount > 0;
        boolean hasHeading = navHeadingCount > 0;
        if (hasGimbal && hasHeading) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.ship_angle"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipPitchDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.RED))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipYawDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.GREEN))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipRollDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.BLUE))
                    .forGoggles(tooltip);
        } else if (hasGimbal) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.gimbal_angle"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipPitchDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.RED))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipRollDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.BLUE))
                    .forGoggles(tooltip);
        } else if (hasHeading) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.heading_angle"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipYawDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.GREEN))
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_orientation"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // --- Velocity ---
        if (hasVelocity) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.ship_velocity"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipVelX, "createdangerclose.unit.velocity").withStyle(ChatFormatting.WHITE))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipVelY, "createdangerclose.unit.velocity").withStyle(ChatFormatting.WHITE))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipVelZ, "createdangerclose.unit.velocity").withStyle(ChatFormatting.WHITE))
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_velocity"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // --- Altitude ---
        if (hasAltitude) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.ship_altitude"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipAltitude, "createdangerclose.unit.distance").withStyle(ChatFormatting.AQUA))
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_altitude"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // --- Target ---
        if (hasTarget) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.target"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(String.format("%.0f", targetX)).withStyle(ChatFormatting.GOLD))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(String.format("%.0f", targetY)).withStyle(ChatFormatting.GOLD))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(String.format("%.0f", targetZ)).withStyle(ChatFormatting.GOLD))
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_target"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // Debug counts
        CDCLang.builder()
                .add(Component.literal(String.format(
                        "  [G:%d H:%d T:%d V:%d A:%d]",
                        gimbalCount, navHeadingCount, navTargetCount, velocityCount, altitudeCount)))
                .style(ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip);

        return true;
    }

    // --- Quaternion helpers ---

    private static double[] eulerFromQuat(Quaterniond q) {
        double sinr_cosp = 2 * (q.w * q.x + q.y * q.z);
        double cosr_cosp = 1 - 2 * (q.x * q.x + q.y * q.y);
        double x = Math.atan2(sinr_cosp, cosr_cosp);

        double sinp = 2 * (q.w * q.y - q.z * q.x);
        double y = Math.abs(sinp) >= 1 ? Math.copySign(Math.PI / 2, sinp) : Math.asin(sinp);

        double siny_cosp = 2 * (q.w * q.z + q.x * q.y);
        double cosy_cosp = 1 - 2 * (q.y * q.y + q.z * q.z);
        double z = Math.atan2(siny_cosp, cosy_cosp);

        return new double[]{x, y, z};
    }

    private static double yawFromQuat(Quaterniond q) {
        return Math.atan2(2 * (q.w * q.y + q.x * q.z),
                1 - 2 * (q.y * q.y + q.x * q.x));
    }
}
