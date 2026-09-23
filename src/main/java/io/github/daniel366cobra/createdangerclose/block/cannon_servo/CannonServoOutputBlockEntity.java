package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import com.simibubi.create.Create;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.jetbrains.annotations.Nullable;

import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountInterfaceBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.index.CBCDataComponents;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonCommonShellProperties;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.fuzes.TimedFuzeItem;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

public class CannonServoOutputBlockEntity extends GeneratingKineticBlockEntity
        implements IHaveGoggleInformation, SensorReading.Provider {

    public static final float MIN_OPERATING_RPM = 32.0f;
    private static final float KEEP_ALIVE_RPM = 1.0f;

    private float desiredGeneratedSpeed = KEEP_ALIVE_RPM;
    private boolean initialSpeedApplied = false;

    public enum Axis { PITCH, YAW, NONE }

    public static final class SlavedMount {
        public final BlockPos mountPos;
        public final @Nullable CannonMountBlockEntity mount;
        public final @Nullable CannonMountInterfaceBlockEntity iface;
        public final Axis axis;

        public float currentAngle;
        public boolean hasShell;
        public @Nullable ResourceLocation shellType;
        public ItemStack fuzeStack = ItemStack.EMPTY;
        public int fuzeTimer = 0;
        public boolean isTimedFuze = false;
        public double gravity;
        public double drag;
        public boolean isQuadraticDrag;
        public float addedChargePower;
        public float minimumChargePower;
        public float totalChargePower;

        public SlavedMount(BlockPos mountPos,
                           @Nullable CannonMountBlockEntity mount,
                           @Nullable CannonMountInterfaceBlockEntity iface,
                           Axis axis) {
            this.mountPos = mountPos;
            this.mount = mount;
            this.iface = iface;
            this.axis = axis;
        }
    }

    private final List<SlavedMount> slavedMounts = new ArrayList<>();
    private Axis detectedAxis = Axis.NONE;
    private boolean isPowered = false;

    private WeakReference<SubLevel> subLevelReference;

    public CannonServoOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.subLevelReference = new WeakReference<>(null);
    }

    @Override
    public void initialize() {
        super.initialize();
        this.subLevelReference = new WeakReference<>(
                Sable.HELPER.getContaining(this.getLevel(), this.worldPosition));
    }

    @Override
    public boolean isSource() {
        return true;
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;

        if (!initialSpeedApplied) {
            initialSpeedApplied = true;
            updateGeneratedRotation();
        }

        float inputSpeed = Math.abs(getInputSpeed());
        isPowered = inputSpeed >= MIN_OPERATING_RPM;

        pollCannonNetwork();
        applyIdleControl();

        notifyUpdate();
    }

    private float getInputSpeed() {
        Direction inputDir = getBlockState().getValue(FACING).getOpposite();
        BlockPos inputPos = worldPosition.relative(inputDir);
        if (!(level.getBlockEntity(inputPos) instanceof CannonServoInputBlockEntity input)) {
            return 0f;
        }
        return input.getSpeed();
    }

    // ------------------------------------------------------------------
    // Idle control
    // ------------------------------------------------------------------

    private void applyIdleControl() {
        for (SlavedMount sm : slavedMounts) {
            if (sm.iface != null) {
                sm.iface.setSequencedAngleLimit(0);
            }
        }

        applyGeneratedSpeed(isPowered ? KEEP_ALIVE_RPM : 0f);
    }

    private void applyGeneratedSpeed(float rpm) {
        if (Math.abs(desiredGeneratedSpeed - rpm) < 1e-4f) return;
        desiredGeneratedSpeed = rpm;
        if (level != null && !level.isClientSide) {
            updateGeneratedRotation();
        }
    }

    // ------------------------------------------------------------------
    // Network polling (server only)
    // ------------------------------------------------------------------

    private void pollCannonNetwork() {
        KineticNetwork network = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this);
        if (network == null) {
            slavedMounts.clear();
            detectedAxis = Axis.NONE;
            return;
        }

        List<SlavedMount> newMounts = new ArrayList<>();
        Axis axisThisPoll = Axis.NONE;

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member == this) continue;
            if (!(member instanceof CannonMountInterfaceBlockEntity iface)) continue;

            Axis axis;
            if (iface instanceof CannonMountInterfaceBlockEntity.PitchInterface) {
                axis = Axis.PITCH;
            } else if (iface instanceof CannonMountInterfaceBlockEntity.YawInterface) {
                axis = Axis.YAW;
            } else {
                continue;
            }

            if (axisThisPoll == Axis.NONE) {
                axisThisPoll = axis;
            } else if (axisThisPoll != axis) {
                detectedAxis = Axis.NONE;
                slavedMounts.clear();
                return;
            }

            if (!(level.getBlockEntity(iface.getBlockPos()) instanceof CannonMountBlockEntity mount)) {
                continue;
            }

            SlavedMount sm = new SlavedMount(iface.getBlockPos(), mount, iface, axis);
            readMountState(sm);
            newMounts.add(sm);
        }

        this.detectedAxis = axisThisPoll;
        this.slavedMounts.clear();
        this.slavedMounts.addAll(newMounts);
    }

    private void readMountState(SlavedMount sm) {
        PitchOrientedContraptionEntity entity = sm.mount.getContraption();
        if (entity == null) {
            sm.currentAngle = 0;
            return;
        }
        sm.currentAngle = (sm.axis == Axis.PITCH) ? entity.pitch : entity.yaw;

        if (sm.axis == Axis.PITCH) {
            readShellData(sm);
        }
    }

    // ------------------------------------------------------------------
    // Shell reading (pitch servo only)
    // ------------------------------------------------------------------

    private void readShellData(SlavedMount sm) {
        sm.hasShell = false;
        sm.shellType = null;
        sm.fuzeStack = ItemStack.EMPTY;
        sm.fuzeTimer = 0;
        sm.isTimedFuze = false;
        sm.totalChargePower = 0f;

        PitchOrientedContraptionEntity entity = sm.mount.getContraption();
        if (entity == null) return;
        if (!(entity.getContraption() instanceof MountedBigCannonContraption mounted)) return;

        HolderLookup.Provider registries = level.registryAccess();

        for (var entry : mounted.presentBlockEntities.entrySet()) {
            if (!(entry.getValue() instanceof IBigCannonBlockEntity cbe)) continue;
            BigCannonBehavior behavior = cbe.cannonBehavior();
            StructureBlockInfo info = behavior.block();
            if (info.state().isAir()) continue;

            var block = info.state().getBlock();

            if (block instanceof BigCannonPropellantBlock propellant) {
                sm.totalChargePower += Math.max(0f, propellant.getChargePower(info));
                continue;
            }

            if (block instanceof ProjectileBlock<?> projBlock && !sm.hasShell) {
                if (!(block instanceof BigCannonMunitionBlock munition)) continue;

                ItemStack shellStack = munition.getExtractedItem(info, registries);
                ItemStack fuze = FuzedProjectileBlock.getFuzeFromItemStack(shellStack);

                sm.hasShell = true;
                sm.shellType = BuiltInRegistries.BLOCK.getKey(block);
                sm.fuzeStack = fuze;
                sm.isTimedFuze = fuze.getItem() instanceof TimedFuzeItem;
                sm.fuzeTimer = sm.isTimedFuze
                        ? fuze.getOrDefault(CBCDataComponents.FUZE_TIMER, 20)
                        : 0;

                EntityType<?> projectileType = projBlock.getAssociatedEntityType();
                BigCannonCommonShellProperties props =
                        CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE
                                .getPropertiesOf(projectileType);

                sm.gravity = props.ballistics().gravity();
                sm.drag = props.ballistics().drag();
                sm.isQuadraticDrag = props.ballistics().isQuadraticDrag();
                sm.addedChargePower = props.bigCannonProperties().addedChargePower();
                sm.minimumChargePower = props.bigCannonProperties().minimumChargePower();
            }
        }
    }

    // ------------------------------------------------------------------
    // SensorReading.Provider
    // ------------------------------------------------------------------

    @Override
    public SensorReading getSensorReading() {
        return new SensorReading.None();
    }

    // ------------------------------------------------------------------
    // Kinetic source API
    // ------------------------------------------------------------------

    @Override
    public float getGeneratedSpeed() {
        return desiredGeneratedSpeed;
    }

    @Override
    public float calculateAddedStressCapacity() {
        return 64.0f;
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        compound.putBoolean("IsPowered", isPowered);
        compound.putInt("DetectedAxis", detectedAxis.ordinal());

        ListTag slaves = new ListTag();
        for (SlavedMount sm : slavedMounts) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("MountPos", sm.mountPos.asLong());
            tag.putInt("Axis", sm.axis.ordinal());
            tag.putFloat("CurrentAngle", sm.currentAngle);
            tag.putBoolean("HasShell", sm.hasShell);
            if (sm.shellType != null) {
                tag.putString("ShellType", sm.shellType.toString());
            }
            if (!sm.fuzeStack.isEmpty()) {
                tag.put("FuzeStack", sm.fuzeStack.saveOptional(registries));
            }

            tag.putInt("FuzeTimer", sm.fuzeTimer);
            tag.putBoolean("IsTimedFuze", sm.isTimedFuze);
            tag.putDouble("Gravity", sm.gravity);
            tag.putDouble("Drag", sm.drag);
            tag.putBoolean("QuadraticDrag", sm.isQuadraticDrag);
            tag.putFloat("AddedChargePower", sm.addedChargePower);
            tag.putFloat("MinimumChargePower", sm.minimumChargePower);
            tag.putFloat("TotalChargePower", sm.totalChargePower);
            slaves.add(tag);
        }
        compound.put("Slaves", slaves);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        this.isPowered = compound.getBoolean("IsPowered");

        Axis[] axes = Axis.values();
        int axisOrd = compound.getInt("DetectedAxis");
        this.detectedAxis = (axisOrd >= 0 && axisOrd < axes.length) ? axes[axisOrd] : Axis.NONE;

        if (!clientPacket) return;

        this.slavedMounts.clear();
        if (!compound.contains("Slaves")) return;

        ListTag slaves = compound.getList("Slaves", Tag.TAG_COMPOUND);
        for (int i = 0; i < slaves.size(); i++) {
            CompoundTag tag = slaves.getCompound(i);
            BlockPos mountPos = BlockPos.of(tag.getLong("MountPos"));

            int slaveAxisOrd = tag.getInt("Axis");
            Axis slaveAxis = (slaveAxisOrd >= 0 && slaveAxisOrd < axes.length)
                    ? axes[slaveAxisOrd] : Axis.NONE;

            SlavedMount sm = new SlavedMount(mountPos, null, null, slaveAxis);
            sm.currentAngle = tag.getFloat("CurrentAngle");
            sm.hasShell = tag.getBoolean("HasShell");
            if (tag.contains("ShellType")) {
                sm.shellType = ResourceLocation.tryParse(tag.getString("ShellType"));
            }
            sm.fuzeStack = ItemStack.parseOptional(registries, tag.getCompound("FuzeStack"));
            sm.fuzeTimer = tag.getInt("FuzeTimer");
            sm.isTimedFuze = tag.getBoolean("IsTimedFuze");
            sm.gravity = tag.getDouble("Gravity");
            sm.drag = tag.getDouble("Drag");
            sm.isQuadraticDrag = tag.getBoolean("QuadraticDrag");
            sm.addedChargePower = tag.getFloat("AddedChargePower");
            sm.minimumChargePower = tag.getFloat("MinimumChargePower");
            sm.totalChargePower = tag.getFloat("TotalChargePower");

            this.slavedMounts.add(sm);
        }
    }

    /**
     * Test helper: sets the loaded shell's timed fuze to the given number of ticks.
     * Returns true if a timed fuze was found and updated.
     */
    public boolean setLoadedFuzeTime(int ticks) {
        if (level == null || level.isClientSide()) return false;
        if (slavedMounts.isEmpty()) return false;

        SlavedMount sm = slavedMounts.get(0);
        if (sm.axis != Axis.PITCH) return false;
        if (sm.mount == null) return false;

        PitchOrientedContraptionEntity entity = sm.mount.getContraption();
        if (entity == null) return false;
        if (!(entity.getContraption() instanceof MountedBigCannonContraption mounted)) return false;

        HolderLookup.Provider registries = level.registryAccess();

        for (var entry : mounted.presentBlockEntities.entrySet()) {
            if (!(entry.getValue() instanceof IBigCannonBlockEntity cbe)) continue;
            BlockPos cannonLocalPos = entry.getKey();  // the cannon block's local position
            BigCannonBehavior behavior = cbe.cannonBehavior();
            StructureBlockInfo loadedInfo = behavior.block();
            if (loadedInfo.state().isAir()) continue;
            if (!(loadedInfo.state().getBlock() instanceof ProjectileBlock<?>)) continue;

            // Modify the loaded shell's NBT
            CompoundTag shellNbt = loadedInfo.nbt() != null ? loadedInfo.nbt().copy() : new CompoundTag();
            ItemStack fuzeStack = ItemStack.parseOptional(registries, shellNbt.getCompound("Fuze"));
            if (!(fuzeStack.getItem() instanceof TimedFuzeItem)) return false;

            fuzeStack.set(CBCDataComponents.FUZE_TIMER, ticks);
            shellNbt.put("Fuze", fuzeStack.saveOptional(registries));

            // Replace the loaded block in the behavior
            behavior.loadBlock(new StructureBlockInfo(
                    loadedInfo.pos(), loadedInfo.state(), shellNbt));
            behavior.blockEntity.setChanged();

            // Update the contraption's blocks map entry for the CANNON BLOCK,
            // using the cannon block's own position and state.
            StructureBlockInfo cannonInfo = mounted.getBlocks().get(cannonLocalPos);
            if (cannonInfo == null) return true;  // behavior updated, but map not synced

            CompoundTag beTag = behavior.blockEntity.saveWithFullMetadata(registries);
            beTag.remove("x"); beTag.remove("y"); beTag.remove("z");
            mounted.getBlocks().put(cannonLocalPos, new StructureBlockInfo(
                    cannonInfo.pos(), cannonInfo.state(), beTag));

            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Tooltip
    // ------------------------------------------------------------------

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.cannon_servo_output.title"))
                .forGoggles(tooltip);

        if (!isPowered) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.error.network_too_slow"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
        }

        if (detectedAxis == Axis.NONE) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo_output.no_mount"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            return true;
        }

        CDCLang.builder()
                .add(Component.translatable("createdangerclose.cannon_servo_output.axis",
                        Component.translatable("createdangerclose.cannon_servo_output.axis."
                                + detectedAxis.name().toLowerCase())))
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        if (slavedMounts.isEmpty()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo_output.no_slaves"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
            return true;
        }

        int index = 1;
        for (SlavedMount sm : slavedMounts) {
            renderMount(tooltip, sm, index++);
        }

        return true;
    }

    private void renderMount(List<Component> tooltip, SlavedMount sm, int index) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.cannon_servo_output.mount_header",
                        index, sm.mountPos.toShortString()))
                .style(ChatFormatting.YELLOW)
                .forGoggles(tooltip);

        String angleKey = (sm.axis == Axis.PITCH)
                ? "createdangerclose.cannon_servo_output.mount_angle_pitch"
                : "createdangerclose.cannon_servo_output.mount_angle_yaw";
        CDCLang.builder()
                .add(Component.translatable(angleKey, String.format("%.1f", sm.currentAngle)))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);

        if (sm.axis != Axis.PITCH) return;

        if (sm.hasShell && sm.shellType != null) {
            Component shellName = Component.translatable(
                    "block." + sm.shellType.getNamespace() + "." + sm.shellType.getPath());

            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo_output.mount_shell",
                            shellName))
                    .style(ChatFormatting.GOLD)
                    .forGoggles(tooltip);

            if (sm.isTimedFuze) {
                int seconds = sm.fuzeTimer / 20;
                int ticks = sm.fuzeTimer % 20;
                CDCLang.builder()
                        .add(Component.translatable(
                                "createdangerclose.cannon_servo_output.mount_fuze_timed",
                                seconds, ticks))
                        .style(ChatFormatting.LIGHT_PURPLE)
                        .forGoggles(tooltip);
            } else if (sm.fuzeStack.isEmpty()) {
                CDCLang.builder()
                        .add(Component.translatable(
                                "createdangerclose.cannon_servo_output.mount_fuze_none"))
                        .style(ChatFormatting.LIGHT_PURPLE)
                        .forGoggles(tooltip);
            } else {
                CDCLang.builder()
                        .add(Component.translatable(
                                "createdangerclose.cannon_servo_output.mount_fuze_other",
                                sm.fuzeStack.getHoverName()))
                        .style(ChatFormatting.LIGHT_PURPLE)
                        .forGoggles(tooltip);
            }

            CDCLang.builder()
                    .add(Component.translatable(
                            "createdangerclose.cannon_servo_output.mount_charge",
                            String.format("%.1f", sm.totalChargePower + sm.addedChargePower)))
                    .style(ChatFormatting.DARK_AQUA)
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable(
                            "createdangerclose.cannon_servo_output.mount_shell_empty"))
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
        }
    }
}