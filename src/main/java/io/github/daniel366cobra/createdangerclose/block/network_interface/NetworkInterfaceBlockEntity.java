package io.github.daniel366cobra.createdangerclose.block.network_interface;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
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
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonReading;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ComputerServo;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoLimits;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoStatus;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import rbasamoyai.createbigcannons.cannon_control.fixed_cannon_mount.FixedCannonMountBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlock;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.ComputerBank;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.ComputerModuleBlock;
import io.github.daniel366cobra.createdangerclose.foundation.plot.BattlePlot;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.SensorReading;
import net.minecraft.world.level.block.Block;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.FireMissionConsoleBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.navigation_console.NavigationConsoleBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
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
        implements IHaveGoggleInformation, SensorReading.Provider, ComputerServo {


    private EquipmentHealth health;
    private SensorReading reading = new SensorReading.None();
    private boolean isPowered = false;

    public NetworkInterfaceBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        behaviours.add(health = new EquipmentHealth(this, EquipmentHealth.Breaks.COG, true)
                .lubricated(() -> Math.abs(getSpeed()), () -> isPowered)
                // Driving a computer, it is part of that bank's machinery and shares its oil.
                .sharingLubricationWith(() -> {
                    BallisticComputerBlockEntity computer = getLinkedComputer();
                    return computer != null ? ComputerBank.lubricationGroup(level, computer.getBlockPos())
                            : List.of(worldPosition);
                }));
    }

    /** A jostled interface skews what it passes on. */
    public EquipmentHealth getHealth() {
        return health;
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide()) {
            return;
        }

        float currentSpeed = Math.abs(getSpeed());
        boolean wasPowered = isPowered;
        isPowered = currentSpeed >= CDCConfig.server().fireControl.minOperatingRpm.get();
        if (isPowered != wasPowered)
            notifyUpdate();

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
        // Broken or seized: passes nothing on, and drives nothing.
        if (health.isOutOfOrder()) {
            setSensorReading(new SensorReading.None());
            return;
        }
        BlockPos checkPos = worldPosition.relative(getBlockState().getValue(FACING));
        if (!level.isLoaded(checkPos)) {
            setSensorReading(new SensorReading.None());
            return;
        }

        // Any block of a computer bank: this interface drives the computer, one poll per poll.
        Block facing = level.getBlockState(checkPos).getBlock();
        if (facing instanceof BallisticComputerBlock || facing instanceof ComputerModuleBlock) {
            BallisticComputerBlockEntity computer = ComputerBank.findComputer(level, checkPos);
            setSensorReading(computer != null && computer.pollFrom(this)
                    ? new SensorReading.Computer() : new SensorReading.None());
            return;
        }

        var be = level.getBlockEntity(checkPos);
        setSensorReading(classify(be));
    }

    private SensorReading classify(BlockEntity be) {
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
        if (be instanceof NavigationConsoleBlockEntity console) {
            console.pollFrom(this);
            return new SensorReading.NavConsole();
        }
        if (be instanceof FixedCannonMountBlockEntity mount) {
            // The computer aims (through carrying bearings) and fires this gun.
            ServoStatus.MountSnapshot gun = CannonReading.readFixedGun(mount, level.registryAccess());
            return new SensorReading.FixedGun(gun != null && gun.hasShell());
        }
        if (be instanceof FireMissionConsoleBlockEntity console) {
            console.pollFrom(this);
            return new SensorReading.FireConsole();
        }
        return new SensorReading.None();
    }

    // --- A gun on a fixed cannon mount ---

    @Nullable
    private FixedCannonMountBlockEntity facedFixedMount() {
        if (level == null)
            return null;
        BlockPos facing = worldPosition.relative(getBlockState().getValue(FACING));
        return level.isLoaded(facing) && level.getBlockEntity(facing) instanceof FixedCannonMountBlockEntity mount
                ? mount : null;
    }

    /** Facing a fixed cannon mount: its gun, with no drive of its own. */
    @Override
    @Nullable
    public ServoStatus getServoStatus() {
        FixedCannonMountBlockEntity mount = facedFixedMount();
        if (mount == null || health.isOutOfOrder())
            return null;
        ServoStatus.MountSnapshot gun = CannonReading.readFixedGun(mount, level.registryAccess());
        return new ServoStatus(worldPosition, ServoAxis.FIXED_GUN, isPowered,
                gun != null ? List.of(gun) : List.of(), null, ServoLimits.FULL_ELEVATION);
    }

    /** Nothing to drive, but fuzes are set as a pitch servo would. */
    @Override
    public void receiveFiringSolution(@Nullable FireSolution solution) {
        FixedCannonMountBlockEntity mount = facedFixedMount();
        if (solution == null || !solution.valid() || mount == null || level.isClientSide())
            return;
        ServoStatus.MountSnapshot gun = CannonReading.readFixedGun(mount, level.registryAccess());
        // An autocannon's breech sets each round as it fires it (see CannonReading.orderAutocannonFuzes).
        if (gun != null && gun.autocannon())
            CannonReading.orderAutocannonFuzes(mount.getContraption(),
                    solution.fuzeTicks(), solution.impactDelayTicks(), level.getGameTime());
        if (gun == null || !gun.hasShell())
            return;
        boolean delayed = CannonReading.isDelayedImpactFuze(gun.fuzeStack());
        int wanted = gun.isTimedFuze() ? solution.fuzeTicks() : delayed ? solution.impactDelayTicks() : -1;
        boolean leaveAsLoaded = gun.isTimedFuze() ? wanted <= 0 : wanted < 0;
        if (!leaveAsLoaded && gun.fuzeTimer() != wanted)
            CannonReading.setFixedGunFuze(mount, wanted, level.registryAccess());
    }

    /** Its controller. */
    @Nullable
    public MapTableBlockEntity getFacedMapTable() {
        if (level == null || !(reading instanceof SensorReading.MapTarget))
            return null;
        BlockPos facing = worldPosition.relative(getBlockState().getValue(FACING));
        return level.getBlockEntity(facing) instanceof MapTableBlockEntity table ? table.getControllerBE() : null;
    }

    @Nullable
    public BallisticComputerBlockEntity getLinkedComputer() {
        if (level == null || !(reading instanceof SensorReading.Computer)) {
            return null;
        }
        return ComputerBank.findComputer(level, worldPosition.relative(getBlockState().getValue(FACING)));
    }

    /** Connects the whole table: it shows the ship and plot, and its target goes onto the network. */
    private SensorReading buildMapReading(MapTableBlockEntity mapTable) {
        MapTableBlockEntity controller = mapTable.getControllerBE();
        if (controller == null) {
            return new SensorReading.None();
        }
        controller.markLinked(findComputerPlot());
        // Placed on a pixel: sigma of a pixel over √12.
        MapTableBlockEntity.Sheet sheet = controller.getSheet();
        double sigma = sheet != null ? sheet.blocksPerPixel() / Math.sqrt(12) : 0;
        return new SensorReading.MapTarget(controller.getTarget(), controller.getTargetTick(), sigma);
    }

    private SensorReading buildGimbalReading(GimbalSensorBlockEntity gimbal) {
        // Sub-level frame from the pose, independent of placement; radians.
        return new SensorReading.Gimbal(
                Math.toDegrees(gimbal.getXAngle()),
                Math.toDegrees(gimbal.getZAngle()));
    }

    private SensorReading buildNavReading(NavTableBlockEntity navTable) {
        ItemStack held = navTable.getHeldItem();
        if (held.isEmpty()) {
            return new SensorReading.None();
        }

        if (isMagnetItem(held)) {
            float rawAngle = navTable.getRelativeAngle();
            // Apply UP/DOWN constant correction per mount orientation
            var facing = navTable.getBlockState().getValue(NavTableBlock.FACING);
            double headingDeg;
            if (facing == Direction.UP) {
                headingDeg = 270.0 - rawAngle;
            } else if (facing == Direction.DOWN) {
                headingDeg = rawAngle - 90.0;
            } else {
                return new SensorReading.None(); // misaligned mount
            }
            return new SensorReading.NavHeading(((headingDeg % 360.0) + 360.0) % 360.0);
        }

        // Simulated points a compass without a live lodestone at spawn: not a fire mission.
        if (held.is(Items.COMPASS) && !hasLiveLodestone(held)) {
            return new SensorReading.None();
        }

        var targetPos = navTable.getTargetPosition(true);
        if (targetPos == null) {
            return new SensorReading.None();
        }

        return new SensorReading.NavTarget(targetPos, navTable.getRelativeAngle());
    }

    private BattlePlot findComputerPlot() {
        BallisticComputerBlockEntity computer = findComputer();
        return computer != null ? computer.getPlot() : BattlePlot.EMPTY;
    }

    /** Found through the interface driving it; the computer is not on the network itself. */
    @Nullable
    public BallisticComputerBlockEntity findComputer() {
        KineticNetwork network = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this);
        if (network == null) {
            return null;
        }
        for (KineticBlockEntity member : network.members.keySet()) {
            if (member instanceof NetworkInterfaceBlockEntity other) {
                BallisticComputerBlockEntity computer = other.getLinkedComputer();
                if (computer != null) {
                    return computer;
                }
            }
        }
        return null;
    }

    private SensorReading buildVelocityReading(VelocitySensorBlockEntity velocity) {
        // Measurement axis in sub-level frame, and the signed value along it.
        Vector3d normal = new Vector3d(velocity.getCurrentNormal());
        double magnitude = velocity.getAdjustedVelocity();

        Vector3d localVelocity = normal.mul(magnitude, new Vector3d());

        return new SensorReading.Velocity(localVelocity, normal);
    }

    private static boolean isMagnetItem(ItemStack stack) {
        return (stack.is(SimBlocks.REDSTONE_MAGNET.asItem()));
    }

    private boolean hasLiveLodestone(ItemStack compass) {
        UUID trackerId = compass.get(SimDataComponents.LODESTONE_COMPASS_SUBLEVEL_TRACKER);
        if (trackerId == null) return false;
        LodestoneTrackingMap map = LodestoneTrackingMap.getOrLoad(level);
        return map != null && map.getInformation(trackerId) != null;
    }

    /** Only goggles need it on the client: sent on change. */
    private void setSensorReading(SensorReading newReading) {
        if (newReading.equals(reading))
            return;
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
        health.addToGoggleTooltip(tooltip);

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
            case SensorReading.Computer ignored -> {
                CDCLang.builder()
                        .add(Component.literal("  • "))
                        .add(Component.translatable("createdangerclose.network_interface.computer"))
                        .style(ChatFormatting.GREEN)
                        .forGoggles(tooltip);
                yield true;
            }
            case SensorReading.NavConsole ignored -> {
                CDCLang.builder()
                        .add(Component.literal("  • "))
                        .add(Component.translatable("createdangerclose.network_interface.nav_console"))
                        .style(ChatFormatting.GREEN)
                        .forGoggles(tooltip);
                yield true;
            }
            case SensorReading.FireConsole ignored -> {
                CDCLang.builder()
                        .add(Component.literal("  • "))
                        .add(Component.translatable("createdangerclose.network_interface.fire_console"))
                        .style(ChatFormatting.GREEN)
                        .forGoggles(tooltip);
                yield true;
            }
            case SensorReading.FixedGun g -> {
                CDCLang.builder()
                        .add(Component.literal("  • "))
                        .add(Component.translatable(g.loaded()
                                ? "createdangerclose.network_interface.fixed_gun"
                                : "createdangerclose.network_interface.fixed_gun_empty"))
                        .style(g.loaded() ? ChatFormatting.GREEN : ChatFormatting.GOLD)
                        .forGoggles(tooltip);
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
                    .add(Component.literal(String.format("  %.1f / %d RPM", speed, CDCConfig.server().fireControl.minOperatingRpm.get())))
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