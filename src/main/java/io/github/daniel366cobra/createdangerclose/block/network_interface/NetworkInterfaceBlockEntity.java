package io.github.daniel366cobra.createdangerclose.block.network_interface;

import com.simibubi.create.Create;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorBlockEntity;
import dev.simulated_team.simulated.content.blocks.gimbal_sensor.GimbalSensorBlock;
import dev.simulated_team.simulated.content.blocks.gimbal_sensor.GimbalSensorBlockEntity;
import dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlock;
import dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity;
import dev.simulated_team.simulated.content.blocks.velocity_sensor.VelocitySensorBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.lang.ref.WeakReference;
import java.util.List;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

public class NetworkInterfaceBlockEntity extends KineticBlockEntity
        implements IHaveGoggleInformation, SensorReading.Provider {

    public static final float MIN_OPERATING_RPM = 32.0f;

    private SensorReading reading = new SensorReading.None();
    private boolean isPowered = false;

    private WeakReference<SubLevel> subLevelReference;
    private WeakReference<KineticNetwork> battleNetworkReference;

    public NetworkInterfaceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        subLevelReference = new WeakReference<>(null);
        battleNetworkReference = new WeakReference<>(null);
    }

    @Override
    public void initialize() {
        super.initialize();
        subLevelReference = new WeakReference<>(Sable.HELPER.getContaining(getLevel(), worldPosition));
        battleNetworkReference = new WeakReference<>(Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this));
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide()) {
            return;
        }

        float currentSpeed = Math.abs(getSpeed());
        isPowered = currentSpeed >= MIN_OPERATING_RPM;

        if (!isPowered) {
            if (reading.kind() != SensorReading.Kind.NONE) {
                setSensorReading(new SensorReading.None());
            }
            return;
        }

        int pollInterval = (int) Math.max(1, 640f / currentSpeed);

        if ((level.getGameTime() % pollInterval == 0)) {
            pollSensor();
        }
    }

    void pollSensor() {
        BlockPos checkPos = worldPosition.relative(getBlockState().getValue(FACING));
        if (!level.isLoaded(checkPos)) {
            setSensorReading(new SensorReading.None());
            return;
        }

        var be = level.getBlockEntity(checkPos);
        setSensorReading(classify(be));
    }

    private SensorReading classify(net.minecraft.world.level.block.entity.BlockEntity be) {
        if (be instanceof GimbalSensorBlockEntity gimbal) {
            return buildGimbalReading(gimbal);
        }
        if (be instanceof AltitudeSensorBlockEntity altitude) {
            return new SensorReading.Altitude(altitude.getWorldHeight());
        }
        if (be instanceof VelocitySensorBlockEntity velocity) {
            return buildVelocityReading(velocity);
        }
        if (be instanceof NavTableBlockEntity navTable) {
            return buildNavReading(navTable);
        }
        return new SensorReading.None();
    }

    private SensorReading buildGimbalReading(GimbalSensorBlockEntity gimbal) {
        var mountAxis = gimbal.getBlockState().getValue(GimbalSensorBlock.HORIZONTAL_AXIS);

        // Raw angles in gimbal-local frame. Apply the per-mount correction
        // to produce a consistent sub-level-frame tilt.
        double rawX = gimbal.getXAngle();
        double rawZ = gimbal.getZAngle();

        double tiltX;
        double tiltZ;
        if (mountAxis == net.minecraft.core.Direction.Axis.Z) {
            tiltX = rawX;
            tiltZ = rawZ;
        } else {
            // HORIZONTAL_AXIS == X: base quaternion is a 90-degree Y rotation,
            // so the gimbal's local X measures sub-level Z tilt and vice versa.
            tiltX = rawZ;
            tiltZ = -rawX;
        }

        // Build the tilt quaternion: a rotation about sub-level X by tiltX,
        // then about sub-level Z by tiltZ (order TBD by your convention).
        Quaterniond tilt = new Quaterniond()
                .rotateX(tiltX)
                .rotateZ(tiltZ);

        return new SensorReading.Gimbal(tilt, mountAxis);
    }

    private SensorReading buildNavReading(NavTableBlockEntity navTable) {
        ItemStack held = navTable.getHeldItem();
        if (held.isEmpty()) {
            return new SensorReading.None();
        }

        // Distinguish magnet (heading) from lodestone/map (target).
        // Replace SimulatedItems.MAGNET with the actual registry reference.
        if (isMagnetItem(held)) {
            float rawAngle = navTable.getRelativeAngle();
            // Apply UP/DOWN constant correction per mount orientation
            var facing = navTable.getBlockState().getValue(NavTableBlock.FACING);
            double headingDeg;
            if (facing == net.minecraft.core.Direction.UP) {
                headingDeg = (270.0 - rawAngle) % 360.0;
            } else if (facing == net.minecraft.core.Direction.DOWN) {
                headingDeg = (rawAngle - 90.0) % 360.0;
            } else {
                return new SensorReading.None(); // misaligned mount
            }
            Quaterniond yaw = new Quaterniond()
                    .rotateY(Math.toRadians(headingDeg));
            return new SensorReading.NavHeading(yaw);
        }

        // Lodestone compass or banner map -> target reference
        var targetPos = navTable.getTargetPosition(true);
        if (targetPos == null) {
            return new SensorReading.None();
        }

        Quaterniond bearing = new Quaterniond()
                .rotateY(Math.toRadians(navTable.getRelativeAngle()));

        return new SensorReading.NavTarget(targetPos, bearing);
    }

    private SensorReading buildVelocityReading(VelocitySensorBlockEntity velocity) {
        // The sensor's measurement axis in sub-level frame, and the signed
        // scalar it reports along that axis.
        Vector3d normal = new Vector3d(velocity.getCurrentNormal());
        double magnitude = velocity.getAdjustedVelocity();

        Vector3d localVelocity = normal.mul(magnitude, new Vector3d());

        return new SensorReading.Velocity(localVelocity, normal);
    }

    private static boolean isMagnetItem(ItemStack stack) {
        return (stack.is(SimBlocks.REDSTONE_MAGNET.asItem()));
    }

    private void setSensorReading(SensorReading newReading) {
        reading = newReading;
        notifyUpdate();
    }

    @Override
    public SensorReading getSensorReading() {
        return reading;
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        isPowered = compound.getBoolean("IsPowered");
        if (compound.contains("Reading")) {
            reading = SensorReading.read(compound.getCompound("Reading"), registries);
        } else {
            reading = new SensorReading.None();
        }
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putBoolean("IsPowered", isPowered);
        compound.put("Reading", SensorReading.write(reading, registries));
    }

    // --- GOGGLES TOOLTIP ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.network_interface.title"))
                .forGoggles(tooltip);

        CDCLang.builder()
                .add(Component.translatable("createdangerclose.network_interface.attached_sensor"))
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        boolean anySensor = switch (reading) {
            case SensorReading.Gimbal g -> {
                renderGimbal(tooltip, g);
                yield true;
            }
            case SensorReading.NavHeading n -> {
                renderNavHeading(tooltip, n);
                yield true;
            }
            case SensorReading.NavTarget t -> {
                renderNavTarget(tooltip, t);
                yield true;
            }
            case SensorReading.Velocity v -> {
                renderVelocity(tooltip, v);
                yield true;
            }
            case SensorReading.Altitude a -> {
                renderAltitude(tooltip, a);
                yield true;
            }
            case SensorReading.Rangefinder r -> {
                renderRangefinder(tooltip, r);
                yield true;
            }
            case SensorReading.None ignored -> false;
        };

        if (!anySensor) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.network_interface.no_sensor"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
        }

        if (!isPowered) {
            float speed = Math.abs(getSpeed());
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.error.network_too_slow"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            CDCLang.builder()
                    .add(Component.literal(String.format("  %.1f / %.0f RPM", speed, MIN_OPERATING_RPM)))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }
        return true;
    }

    private void renderGimbal(List<Component> tooltip, SensorReading.Gimbal g) {
        // Extract Euler angles for display (or store them alongside the
        // quaternion if you want exact values back out).
        double[] euler = eulerFromQuat(g.tilt());
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.gimbal_x_angle",
                        String.format("%.1f", Math.toDegrees(euler[0]))))
                .add(Component.translatable("createdangerclose.unit.angle"))
                .style(ChatFormatting.RED)
                .forGoggles(tooltip);
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.gimbal_z_angle",
                        String.format("%.1f", Math.toDegrees(euler[2]))))
                .add(Component.translatable("createdangerclose.unit.angle"))
                .style(ChatFormatting.BLUE)
                .forGoggles(tooltip);
    }

    private void renderNavHeading(List<Component> tooltip, SensorReading.NavHeading n) {
        double headingDeg = Math.toDegrees(yawFromQuat(n.yaw()));
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.nav_y_angle",
                        String.format("%.1f", headingDeg)))
                .add(Component.translatable("createdangerclose.unit.angle"))
                .style(ChatFormatting.GREEN)
                .forGoggles(tooltip);
    }

    private void renderNavTarget(List<Component> tooltip, SensorReading.NavTarget t) {
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.nav_target",
                        String.format("%.0f, %.0f, %.0f", t.worldPos().x, t.worldPos().y, t.worldPos().z)))
                .style(ChatFormatting.GOLD)
                .forGoggles(tooltip);
    }

    private void renderVelocity(List<Component> tooltip, SensorReading.Velocity v) {
        double speed = v.localVelocity().length();
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.velocity",
                        String.format("%.2f", speed)))
                .add(Component.translatable("createdangerclose.unit.velocity"))
                .style(ChatFormatting.WHITE)
                .forGoggles(tooltip);
    }

    private void renderAltitude(List<Component> tooltip, SensorReading.Altitude a) {
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.altitude",
                        String.format("%.1f", a.worldHeight())))
                .add(Component.translatable("createdangerclose.unit.distance"))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);
    }

    private void renderRangefinder(List<Component> tooltip, SensorReading.Rangefinder r) {
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.rangefinder",
                        String.format("%.1f m", r.rangeMeters())))
                .add(Component.translatable("createdangerclose.unit.distance"))
                .style(ChatFormatting.LIGHT_PURPLE)
                .forGoggles(tooltip);
    }

    // --- Helpers (place in a util class eventually) ---

    private static double[] eulerFromQuat(Quaterniond q) {
        // Standard XYZ Euler extraction. Adjust to match your convention.
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