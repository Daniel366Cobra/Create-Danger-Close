package io.github.daniel366cobra.createdangerclose.block.navigation_console;

import com.simibubi.create.Create;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlockEntity;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.SensorReading;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.List;

/**
 * The ship's own attitude, speed and altitude, read through goggles. Connected by a facing
 * interface ({@link #pollFrom}). Values come from the Sable pose; sensors only gate what is shown.
 */
public class NavigationConsoleBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    // A powered interface polls at least every 20 ticks.
    private static final int LINK_TIMEOUT_TICKS = 40;

    private boolean linked = false;
    private long lastLinkTick;

    // --- Sensor gates: what is on the battle network ---
    private int gimbalCount = 0;
    private int navHeadingCount = 0;
    private int altitudeCount = 0;
    // Per ship axis (X, Y, Z).
    private final int[] velocityAxisCounts = new int[3];

    // --- Own-ship state from the sublevel pose ---
    private boolean hasPose = false;
    private double shipPitchDeg = 0;
    private double shipHeadingDeg = 0;
    private double shipRollDeg = 0;
    // Ship frame, m/s.
    private double shipVelX = 0;
    private double shipVelY = 0;
    private double shipVelZ = 0;
    private double shipAltitude = 0;

    // Client: instruments ease toward the latest readings; zero without their sensor.
    private static final float INSTRUMENT_EASING = 0.3f;
    /** Blocks per second (half a needle turn). */
    public static final double SPEEDOMETER_FULL_SCALE = 40;
    private final LerpedFloat ballHeading = LerpedFloat.angular().startWithValue(0);
    private final LerpedFloat ballPitch = LerpedFloat.angular().startWithValue(0);
    private final LerpedFloat ballRoll = LerpedFloat.angular().startWithValue(0);
    private final LerpedFloat altimeter = LerpedFloat.linear().startWithValue(0);
    private final LerpedFloat speedometer = LerpedFloat.linear().startWithValue(0);

    public NavigationConsoleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    /** Called by a powered facing interface each time it polls. */
    public void pollFrom(NetworkInterfaceBlockEntity networkInterface) {
        if (level == null || level.isClientSide())
            return;
        linked = true;
        lastLinkTick = level.getGameTime();
        countSensors(Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(networkInterface));
        readOwnShipState();
        notifyUpdate();
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null)
            return;
        if (level.isClientSide()) {
            tickInstruments();
            return;
        }
        if (linked && level.getGameTime() - lastLinkTick > LINK_TIMEOUT_TICKS) {
            linked = false;
            notifyUpdate();
        }
    }

    // --- Instruments (client) ---

    private void tickInstruments() {
        boolean live = linked && hasPose;
        boolean attitude = live && gimbalCount > 0;
        ballHeading.chase(live && navHeadingCount > 0 ? shipHeadingDeg : 0, INSTRUMENT_EASING, LerpedFloat.Chaser.EXP);
        ballPitch.chase(attitude ? shipPitchDeg : 0, INSTRUMENT_EASING, LerpedFloat.Chaser.EXP);
        ballRoll.chase(attitude ? shipRollDeg : 0, INSTRUMENT_EASING, LerpedFloat.Chaser.EXP);

        // Bedrock to build height.
        double altitudeShown = live && altitudeCount > 0
                ? (shipAltitude - level.getMinBuildHeight()) / (level.getMaxBuildHeight() - level.getMinBuildHeight()) : 0;
        altimeter.chase(Mth.clamp(altitudeShown, 0, 1), INSTRUMENT_EASING, LerpedFloat.Chaser.EXP);

        double vx = velocityAxisCounts[0] > 0 ? shipVelX : 0;
        double vy = velocityAxisCounts[1] > 0 ? shipVelY : 0;
        double vz = velocityAxisCounts[2] > 0 ? shipVelZ : 0;
        double speed = live ? Math.sqrt(vx * vx + vy * vy + vz * vz) : 0;
        speedometer.chase(Double.isFinite(speed) ? Mth.clamp(speed / SPEEDOMETER_FULL_SCALE, 0, 1) : 0,
                INSTRUMENT_EASING, LerpedFloat.Chaser.EXP);

        ballHeading.tickChaser();
        ballPitch.tickChaser();
        ballRoll.tickChaser();
        altimeter.tickChaser();
        speedometer.tickChaser();
    }

    /** Degrees (Minecraft yaw: 0 = south, 90 = west); 0 without a heading sensor. */
    public float getBallHeading(float partialTicks) {
        return ballHeading.getValue(partialTicks);
    }

    /** Degrees, nose up positive; 0 without a Gimbal Sensor. */
    public float getBallPitch(float partialTicks) {
        return ballPitch.getValue(partialTicks);
    }

    /** Degrees; 0 without a Gimbal Sensor. */
    public float getBallRoll(float partialTicks) {
        return ballRoll.getValue(partialTicks);
    }

    /** 0 (bedrock) to 1 (build height); 0 without an Altitude Sensor. */
    public float getAltimeter(float partialTicks) {
        return altimeter.getValue(partialTicks);
    }

    /** 0 to 1 (full scale); 0 without Velocity Sensors. */
    public float getSpeedometer(float partialTicks) {
        return speedometer.getValue(partialTicks);
    }

    private void countSensors(KineticNetwork network) {
        gimbalCount = navHeadingCount = altitudeCount = 0;
        Arrays.fill(velocityAxisCounts, 0);
        if (network == null)
            return;
        for (KineticBlockEntity member : network.members.keySet()) {
            if (!(member instanceof SensorReading.Provider provider))
                continue;
            switch (provider.getSensorReading()) {
                case SensorReading.Gimbal ignored -> gimbalCount++;
                case SensorReading.NavHeading ignored -> navHeadingCount++;
                case SensorReading.Velocity v -> velocityAxisCounts[v.dominantAxis()]++;
                case SensorReading.Altitude ignored -> altitudeCount++;
                default -> { }
            }
        }
    }

    /** In the ship's frame: whoever faces the controls faces the bow. */
    public Direction getBow() {
        return getBlockState().getValue(NavigationConsoleBlock.FACING).getOpposite();
    }

    private void readOwnShipState() {
        hasPose = false;
        SubLevel subLevel = Sable.HELPER.getContaining(level, worldPosition);
        if (subLevel == null)
            return;

        Quaterniondc q = subLevel.logicalPose().orientation();

        // Heading/pitch of the bow, roll of the starboard axis about it.
        Vector3d bow = new Vector3d(getBow().getStepX(), 0, getBow().getStepZ());
        Vector3d forward = q.transform(new Vector3d(bow));
        Vector3d right = q.transform(new Vector3d(0, 1, 0).cross(bow));
        Vector3d up = q.transform(new Vector3d(0, 1, 0));
        double heading = Math.toDegrees(Math.atan2(-forward.x, forward.z));
        shipHeadingDeg = heading < 0 ? heading + 360 : heading;
        shipPitchDeg = Math.toDegrees(Math.asin(Math.clamp(forward.y, -1.0, 1.0)));
        shipRollDeg = Math.toDegrees(Math.atan2(right.y, up.y));

        Vector3d center = new Vector3d(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5);
        Vector3d worldVel = Sable.HELPER.getVelocity(level, subLevel, center, new Vector3d());
        Vector3d shipVel = q.transformInverse(worldVel, new Vector3d());
        shipVelX = shipVel.x;
        shipVelY = shipVel.y;
        shipVelZ = shipVel.z;

        shipAltitude = subLevel.logicalPose().transformPosition(center, new Vector3d()).y;
        hasPose = true;
    }

    // --- NBT ---

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        linked = compound.getBoolean("Linked");
        gimbalCount = compound.getInt("GimbalCount");
        navHeadingCount = compound.getInt("NavHeadingCount");
        altitudeCount = compound.getInt("AltitudeCount");
        velocityAxisCounts[0] = compound.getInt("VelocityCountX");
        velocityAxisCounts[1] = compound.getInt("VelocityCountY");
        velocityAxisCounts[2] = compound.getInt("VelocityCountZ");
        hasPose = compound.getBoolean("HasPose");
        shipPitchDeg = compound.getDouble("ShipPitch");
        shipHeadingDeg = compound.getDouble("ShipHeading");
        shipRollDeg = compound.getDouble("ShipRoll");
        shipVelX = compound.getDouble("VelX");
        shipVelY = compound.getDouble("VelY");
        shipVelZ = compound.getDouble("VelZ");
        shipAltitude = compound.getDouble("Altitude");
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        // Only clients need these: readings are live, and the link lapses on its own.
        if (!clientPacket)
            return;
        compound.putBoolean("Linked", linked);
        compound.putInt("GimbalCount", gimbalCount);
        compound.putInt("NavHeadingCount", navHeadingCount);
        compound.putInt("AltitudeCount", altitudeCount);
        compound.putInt("VelocityCountX", velocityAxisCounts[0]);
        compound.putInt("VelocityCountY", velocityAxisCounts[1]);
        compound.putInt("VelocityCountZ", velocityAxisCounts[2]);
        compound.putBoolean("HasPose", hasPose);
        compound.putDouble("ShipPitch", shipPitchDeg);
        compound.putDouble("ShipHeading", shipHeadingDeg);
        compound.putDouble("ShipRoll", shipRollDeg);
        compound.putDouble("VelX", shipVelX);
        compound.putDouble("VelY", shipVelY);
        compound.putDouble("VelZ", shipVelZ);
        compound.putDouble("Altitude", shipAltitude);
    }

    // --- Tooltip ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.navigation_console.title"))
                .forGoggles(tooltip);

        if (!linked) {
            line(tooltip, "not_connected", ChatFormatting.RED);
            return true;
        }
        if (!hasPose) {
            line(tooltip, "no_sublevel", ChatFormatting.DARK_RED);
            return true;
        }

        // --- Attitude: pitch and roll need a gimbal, heading a heading sensor ---
        boolean hasGimbal = gimbalCount > 0;
        boolean hasHeading = navHeadingCount > 0;
        if (hasGimbal && hasHeading) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.navigation_console.ship_angle"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipPitchDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.RED))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipHeadingDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.GREEN))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipRollDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.BLUE))
                    .forGoggles(tooltip);
        } else if (hasGimbal) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.navigation_console.gimbal_angle"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipPitchDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.RED))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipRollDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.BLUE))
                    .forGoggles(tooltip);
        } else if (hasHeading) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.navigation_console.heading_angle"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipHeadingDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.GREEN))
                    .forGoggles(tooltip);
        }
        if (!hasGimbal)
            line(tooltip, "no_gimbal", ChatFormatting.DARK_GRAY);
        if (!hasHeading)
            line(tooltip, "no_heading", ChatFormatting.DARK_GRAY);

        // --- Velocity: one ship axis per velocity sensor ---
        int axesCovered = 0;
        for (int count : velocityAxisCounts)
            if (count > 0)
                axesCovered++;
        if (axesCovered > 0) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.navigation_console.ship_velocity"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(velocityComponent(0, shipVelX))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(velocityComponent(1, shipVelY))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(velocityComponent(2, shipVelZ))
                    .forGoggles(tooltip);
        } else {
            line(tooltip, "no_velocity", ChatFormatting.DARK_GRAY);
        }

        // --- Altitude ---
        if (altitudeCount > 0) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.navigation_console.ship_altitude"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipAltitude, "createdangerclose.unit.distance").withStyle(ChatFormatting.AQUA))
                    .forGoggles(tooltip);
        } else {
            line(tooltip, "no_altitude", ChatFormatting.DARK_GRAY);
        }
        return true;
    }

    private static void line(List<Component> tooltip, String key, ChatFormatting style) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.navigation_console." + key))
                .style(style)
                .forGoggles(tooltip);
    }

    /** A dash if no sensor covers that axis. */
    private MutableComponent velocityComponent(int axis, double value) {
        if (velocityAxisCounts[axis] == 0)
            return Component.literal("--").withStyle(ChatFormatting.DARK_GRAY);
        return CDCLang.numberWithUnit(value, "createdangerclose.unit.velocity").withStyle(ChatFormatting.WHITE);
    }

}