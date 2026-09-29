package io.github.daniel366cobra.createdangerclose.block.network_interface;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorBlockEntity;
import dev.simulated_team.simulated.content.blocks.gimbal_sensor.GimbalSensorBlockEntity;
import dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlock;
import dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity;
import dev.simulated_team.simulated.content.blocks.velocity_sensor.VelocitySensorBlockEntity;
import dev.simulated_team.simulated.content.navigation_targets.lodestone_compass_compatability.LodestoneTrackingMap;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.index.SimDataComponents;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import io.github.daniel366cobra.createdangerclose.api.plot.BattlePlot;
import io.github.daniel366cobra.createdangerclose.block.fire_control_solver.FireControlSolverBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

public class NetworkInterfaceBlockEntity extends KineticBlockEntity
        implements IHaveGoggleInformation, SensorReading.Provider {

    public static final float MIN_OPERATING_RPM = 32.0f;

    private SensorReading reading = new SensorReading.None();
    private boolean isPowered = false;

    public NetworkInterfaceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
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
        if (be instanceof MapTableBlockEntity mapTable) {
            return buildMapReading(mapTable);
        }
        return new SensorReading.None();
    }

    /**
     * Any block of a map table connects the whole table: it now shows the
     * ship and the solver's plot, and its target (if any) goes onto the
     * network. This is the one place data flows back from the network.
     */
    private SensorReading buildMapReading(MapTableBlockEntity mapTable) {
        MapTableBlockEntity controller = mapTable.getControllerBE();
        if (controller == null) {
            return new SensorReading.None();
        }
        controller.markLinked(findSolverPlot());
        return new SensorReading.MapTarget(controller.getTarget(), controller.getTargetTick());
    }

    private SensorReading buildGimbalReading(GimbalSensorBlockEntity gimbal) {
        // The gimbal computes its angles in sub-level frame from the pose,
        // independent of the block's placement, and reports them in radians.
        return new SensorReading.Gimbal(
                Math.toDegrees(gimbal.getXAngle()),
                Math.toDegrees(gimbal.getZAngle()));
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
                headingDeg = 270.0 - rawAngle;
            } else if (facing == net.minecraft.core.Direction.DOWN) {
                headingDeg = rawAngle - 90.0;
            } else {
                return new SensorReading.None(); // misaligned mount
            }
            return new SensorReading.NavHeading(((headingDeg % 360.0) + 360.0) % 360.0);
        }

        // Simulated points a compass without a live lodestone at world spawn.
        // That is not a fire mission, so report no target instead.
        if (held.is(Items.COMPASS) && !hasLiveLodestone(held)) {
            return new SensorReading.None();
        }

        // Lodestone compass or banner map -> target reference
        var targetPos = navTable.getTargetPosition(true);
        if (targetPos == null) {
            return new SensorReading.None();
        }

        return new SensorReading.NavTarget(targetPos, navTable.getRelativeAngle());
    }

    /** The plot of the fire control solver on this interface's network, or an empty one. */
    private BattlePlot findSolverPlot() {
        KineticNetwork network = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this);
        if (network == null) {
            return BattlePlot.EMPTY;
        }
        for (KineticBlockEntity member : network.members.keySet()) {
            if (member instanceof FireControlSolverBlockEntity solver) {
                return solver.getPlot();
            }
        }
        return BattlePlot.EMPTY;
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

    /** Whether the compass is bound to a lodestone that still exists. */
    private boolean hasLiveLodestone(ItemStack compass) {
        UUID trackerId = compass.get(SimDataComponents.LODESTONE_COMPASS_SUBLEVEL_TRACKER);
        if (trackerId == null) return false;
        LodestoneTrackingMap map = LodestoneTrackingMap.getOrLoad(level);
        return map != null && map.getInformation(trackerId) != null;
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
            case SensorReading.MapTarget m -> {
                renderMapTarget(tooltip, m);
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
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.gimbal_x_angle",
                        String.format("%.1f", g.xAngleDeg())))
                .add(Component.translatable("createdangerclose.unit.angle"))
                .style(ChatFormatting.RED)
                .forGoggles(tooltip);
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.gimbal_z_angle",
                        String.format("%.1f", g.zAngleDeg())))
                .add(Component.translatable("createdangerclose.unit.angle"))
                .style(ChatFormatting.BLUE)
                .forGoggles(tooltip);
    }

    private void renderNavHeading(List<Component> tooltip, SensorReading.NavHeading n) {
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(Component.translatable("createdangerclose.network_interface.nav_y_angle",
                        String.format("%.1f", n.headingDeg())))
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

    private void renderMapTarget(List<Component> tooltip, SensorReading.MapTarget m) {
        Vec3 pos = m.worldPos();
        CDCLang.builder()
                .add(Component.literal("  • "))
                .add(pos != null
                        ? Component.translatable("createdangerclose.network_interface.map_target",
                                String.format("%.0f, %.0f, %.0f", pos.x, pos.y, pos.z))
                        : Component.translatable("createdangerclose.network_interface.map_no_target"))
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
}