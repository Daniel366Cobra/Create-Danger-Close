package io.github.daniel366cobra.createdangerclose.damage;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.config.CServer;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import io.github.daniel366cobra.createdangerclose.foundation.damage.BattleDamageable;
import io.github.daniel366cobra.createdangerclose.foundation.damage.DamagePart;
import io.github.daniel366cobra.createdangerclose.foundation.damage.Fault;
import io.github.daniel366cobra.createdangerclose.registries.CDCFluids;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.registries.CDCStats;
import io.github.daniel366cobra.createdangerclose.registries.CDCTriggers;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Health record of mechanical equipment: faults and jostle bias. Saves, syncs, takes battle damage
 * and writes its goggles lines; the equipment only asks {@link #isOutOfOrder()} and the biases.
 */
public class EquipmentHealth extends BlockEntityBehaviour implements BattleDamageable {

    public static final BehaviourType<EquipmentHealth> TYPE = new BehaviourType<>();

    // A jostle's bias is between this share of the maximum (config) and the maximum.
    private static final float MIN_BIAS_SHARE = 0.4f;

    public enum Breaks { COG, MECHANISM }

    private final Breaks breaks;
    private final boolean canJostle;
    private Supplier<AABB> box;

    private final Set<Fault> faults = EnumSet.noneOf(Fault.class);

    // --- Lubrication (see lubricated): dries faster at higher RPM, so readiness costs upkeep ---
    private static final float TICKS_PER_DAY = 24000;
    private static final float FULL_SPEED_RPM = 256;
    private static final int MAINTENANCE_INTERVAL_TICKS = 20;
    @Nullable private Supplier<Float> networkRpm;
    private BooleanSupplier running = () -> false;
    private Supplier<Collection<BlockPos>> lubricationGroup = () -> List.of(getPos());
    private float lubrication = 1;
    // Filled by pipe; tops up its lubrication group.
    private static final int RESERVOIR_MB = 1000;
    private final FluidTank reservoir = new FluidTank(RESERVOIR_MB, EquipmentHealth::isLubricant) {
        @Override
        protected void onContentsChanged() {
            blockEntity.setChanged();
            reservoirChanged = true;
        }
    };
    // Synced with the next maintenance tick rather than every drop.
    private boolean reservoirChanged;
    // Freed by oil piped in: whoever stands this close sees it.
    private static final double UNSEIZE_WITNESS_RANGE = 2.5;
    // Degrees, while jostled.
    private float biasYaw, biasPitch;

    /**
     * @param canJostle whether a lighter blast jostles it
     */
    public EquipmentHealth(SmartBlockEntity be, Breaks breaks, boolean canJostle) {
        super(be);
        this.breaks = breaks;
        this.canJostle = canJostle;
        this.box = () -> new AABB(be.getBlockPos());
    }

    /**
     * Wears with network speed; seizes when dry.
     *
     * @param running only then can it seize
     */
    public EquipmentHealth lubricated(Supplier<Float> networkRpm, BooleanSupplier running) {
        this.networkRpm = networkRpm;
        this.running = running;
        return this;
    }

    /** One honeycomb click serves the whole group. */
    public EquipmentHealth sharingLubricationWith(Supplier<Collection<BlockPos>> group) {
        this.lubricationGroup = group;
        return this;
    }

    /** Default: its block. */
    public EquipmentHealth withBox(Supplier<AABB> box) {
        this.box = box;
        return this;
    }

    @Override
    public BehaviourType<?> getType() {
        return TYPE;
    }

    @Nullable
    public static EquipmentHealth of(@Nullable net.minecraft.world.level.block.entity.BlockEntity be) {
        return be == null ? null : BlockEntityBehaviour.get(be, TYPE);
    }

    // --- What the equipment asks ---

    /** Broken or seized. */
    public boolean isOutOfOrder() {
        for (Fault fault : faults)
            if (fault.isOutOfOrder())
                return true;
        return false;
    }

    public boolean has(Fault fault) {
        return faults.contains(fault);
    }

    public float biasYaw() {
        return faults.contains(Fault.JOSTLED) ? biasYaw : 0;
    }

    public float biasPitch() {
        return faults.contains(Fault.JOSTLED) ? biasPitch : 0;
    }

    // --- Damage and repair ---

    public void jostle() {
        if (!canJostle || faults.contains(Fault.JOSTLED))
            return;
        Level level = getWorld();
        float direction = level.random.nextFloat() * Mth.TWO_PI;
        float maxBias = CDCConfig.server().battleDamage.maxJostleBias.getF();
        float size = maxBias * Mth.lerp(level.random.nextFloat(), MIN_BIAS_SHARE, 1);
        biasYaw = size * Mth.cos(direction);
        biasPitch = size * Mth.sin(direction);
        faults.add(Fault.JOSTLED);
        level.playSound(null, getPos(), SoundEvents.CHAIN_FALL, SoundSource.BLOCKS, 1f, 0.6f);
        changed();
    }

    public void breakDown() {
        Fault broken = breaks == Breaks.COG ? Fault.BROKEN_COG : Fault.BROKEN_MECHANISM;
        if (faults.contains(broken))
            return;
        faults.add(broken);
        getWorld().playSound(null, getPos(), SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, 1f, 0.6f);
        changed();
    }

    /** Returns whether the fault was there. */
    public boolean repair(Fault fault) {
        if (!faults.remove(fault))
            return false;
        changed();
        return true;
    }

    /** Ponder only: no sound, nothing to sync. */
    public void setPonderFault(Fault fault, boolean present) {
        if (present)
            faults.add(fault);
        else
            faults.remove(fault);
    }

    private void changed() {
        blockEntity.setChanged();
        blockEntity.sendData();
    }

    // --- Particles (client): what is wrong shows from across the deck ---

    @Override
    public void tick() {
        super.tick();
        Level level = getWorld();
        if (!level.isClientSide) {
            if (networkRpm != null && level.getGameTime() % MAINTENANCE_INTERVAL_TICKS == 0
                    && CDCConfig.server().maintenance.lubrication.allowWearAndSeizing.get())
                wearLubrication();
            return;
        }
        if (faults.isEmpty() || !CDCConfig.client().damageParticles.get())
            return;
        RandomSource random = level.random;
        AABB part = box.get();
        if (isOutOfOrder()) {
            // Broken or seized: a steady smoke, and the odd spark.
            if (random.nextInt(4) == 0)
                emit(level, part, ParticleTypes.SMOKE, 0.02);
            if (random.nextInt(20) == 0)
                emit(level, part, ParticleTypes.LARGE_SMOKE, 0.01);
            if (random.nextInt(30) == 0)
                emit(level, part, ParticleTypes.ELECTRIC_SPARK, 0.05);
        } else if (faults.contains(Fault.JOSTLED) && random.nextInt(40) == 0) {
            // Jostled: a thin puff now and then.
            emit(level, part, ParticleTypes.SMOKE, 0.01);
        }
    }

    // --- Lubrication ---

    private void wearLubrication() {
        float rpm = Math.min(Math.abs(networkRpm.get()), FULL_SPEED_RPM);
        CServer.Lubrication config = CDCConfig.server().maintenance.lubrication;
        float daysDry = Mth.lerp(rpm / FULL_SPEED_RPM, config.daysDryAtRest.getF(), config.daysDryAtFullSpeed.getF());
        float seizeBelow = config.seizeBelow.getF();
        float before = lubrication;
        lubrication = Math.max(0, lubrication - MAINTENANCE_INTERVAL_TICKS / (daysDry * TICKS_PER_DAY));
        if (running.getAsBoolean() && lubrication < seizeBelow && !faults.contains(Fault.SEIZED)) {
            float perMinute = config.seizesPerMinuteDry.getF() * (seizeBelow - lubrication) / seizeBelow;
            if (getWorld().random.nextFloat() < perMinute * MAINTENANCE_INTERVAL_TICKS / 1200f)
                seize();
        }
        drawFromReservoir();
        if ((int) (before * 100) != (int) (lubrication * 100))
            changed();
        else if (reservoirChanged)
            changed();
        reservoirChanged = false;
    }

    private void seize() {
        faults.add(Fault.SEIZED);
        getWorld().playSound(null, getPos(), SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 1f, 0.5f);
        changed();
    }

    public boolean isLubricated() {
        return networkRpm != null;
    }

    /** Itself included. */
    private List<EquipmentHealth> lubricationGroup() {
        List<EquipmentHealth> group = new ArrayList<>();
        for (BlockPos pos : lubricationGroup.get()) {
            EquipmentHealth member = at(getWorld(), pos);
            if (member != null && member.isLubricated() && !group.contains(member))
                group.add(member);
        }
        if (!group.contains(this))
            group.add(this);
        return group;
    }

    // --- The reservoir ---

    private static boolean isLubricant(FluidStack fluid) {
        return fluid.is(CDCFluids.STRONG_LUBRICANTS) || fluid.is(CDCFluids.MEDIUM_LUBRICANTS)
                || fluid.is(CDCFluids.WEAK_LUBRICANTS);
    }

    /** mB; medium is the config's figure, weak and strong lubricate that step less and more per mB. In several tags, the strongest. */
    private int fluidPerBlock() {
        CServer.Lubrication config = CDCConfig.server().maintenance.lubrication;
        FluidStack fluid = reservoir.getFluid();
        double power = fluid.is(CDCFluids.STRONG_LUBRICANTS) ? 1 + config.lubricantTierStep.get()
                : fluid.is(CDCFluids.MEDIUM_LUBRICANTS) ? 1 : 1 - config.lubricantTierStep.get();
        return Math.max(1, (int) Math.round(config.lubricantPerBlock.get() / power));
    }

    /** Members at least a percent below full take what they need; that frees a seized one. */
    private void drawFromReservoir() {
        if (reservoir.isEmpty())
            return;
        int perBlock = fluidPerBlock();
        for (EquipmentHealth member : lubricationGroup()) {
            float need = 1 - member.lubrication;
            if (need < 0.01f && !member.faults.contains(Fault.SEIZED))
                continue;
            int wanted = Math.max(1, Mth.ceil(need * perBlock));
            int drawn = reservoir.drain(wanted, IFluidHandler.FluidAction.EXECUTE).getAmount();
            if (drawn <= 0)
                return;
            member.lubrication = Math.min(1, member.lubrication + (float) drawn / perBlock);
            if (member.faults.remove(Fault.SEIZED) && member.isServo())
                member.creditUnseizedNearby();
            member.changed();
            if (reservoir.isEmpty())
                return;
        }
    }

    /** Takes lubricants in, gives nothing back. */
    @Nullable
    public IFluidHandler getReservoir() {
        if (!isLubricated())
            return null;
        return new IFluidHandler() {
            @Override
            public int getTanks() {
                return 1;
            }

            @Override
            public FluidStack getFluidInTank(int tank) {
                return reservoir.getFluid();
            }

            @Override
            public int getTankCapacity(int tank) {
                return RESERVOIR_MB;
            }

            @Override
            public boolean isFluidValid(int tank, FluidStack stack) {
                return isLubricant(stack);
            }

            @Override
            public int fill(FluidStack resource, FluidAction action) {
                return reservoir.fill(resource, action);
            }

            @Override
            public FluidStack drain(FluidStack resource, FluidAction action) {
                return FluidStack.EMPTY;
            }

            @Override
            public FluidStack drain(int maxDrain, FluidAction action) {
                return FluidStack.EMPTY;
            }
        };
    }

    public boolean lubricate(Player player, ItemStack held) {
        if (!isLubricated())
            return false;
        List<EquipmentHealth> group = lubricationGroup();
        float total = 0;
        boolean seized = false, seizedServo = false;
        for (EquipmentHealth member : group) {
            total += member.lubrication;
            seized |= member.faults.contains(Fault.SEIZED);
            seizedServo |= member.faults.contains(Fault.SEIZED) && member.isServo();
        }
        float deficit = group.size() - total;
        int wanted = Math.max(Mth.ceil(deficit - 0.01f), seized ? 1 : 0);
        if (wanted <= 0)
            return false;
        int combs = player.getAbilities().instabuild ? wanted : Math.min(wanted, held.getCount());
        float each = Math.min(1, (total + combs) / group.size());
        for (EquipmentHealth member : group) {
            member.lubrication = each;
            member.faults.remove(Fault.SEIZED);
            member.changed();
        }
        if (!player.getAbilities().instabuild)
            held.shrink(combs);
        // Freeing a seized mechanism puts it back in order: a repair. Topping up is upkeep.
        if (seized)
            CDCStats.EQUIPMENT_REPAIRED.awardTo(player);
        if (seizedServo) {
            CDCTriggers.EQUIPMENT_MALFUNCTION.get().trigger(player);
            CDCTriggers.SERVO_UNSEIZED.get().trigger(player);
        }
        return true;
    }

    private boolean isServo() {
        return blockEntity instanceof CannonServoOutputBlockEntity;
    }

    private void creditUnseizedNearby() {
        Vec3 center = Sable.HELPER.projectOutOfSubLevel(getWorld(), Vec3.atCenterOf(getPos()));
        for (Player player : getWorld().players())
            if (player.position().distanceTo(center) <= UNSEIZE_WITNESS_RANGE) {
                CDCTriggers.EQUIPMENT_MALFUNCTION.get().trigger(player);
                CDCTriggers.SERVO_UNSEIZED.get().trigger(player);
            }
    }

    private static void emit(Level level, AABB part, ParticleOptions particle, double speed) {
        RandomSource random = level.random;
        double x = Mth.lerp(random.nextDouble(), part.minX + 0.1, part.maxX - 0.1);
        double z = Mth.lerp(random.nextDouble(), part.minZ + 0.1, part.maxZ - 0.1);
        level.addParticle(particle, x, part.maxY + 0.02, z, 0, speed, 0);
    }

    // --- Battle damage ---

    @Override
    public void initialize() {
        super.initialize();
        if (!getWorld().isClientSide)
            BattleDamage.track(this);
    }

    @Override
    public void unload() {
        super.unload();
        BattleDamage.untrack(this);
    }

    @Override
    public void destroy() {
        super.destroy();
        BattleDamage.untrack(this);
    }

    @Override
    @Nullable
    public Level damageLevel() {
        return blockEntity.isRemoved() ? null : getWorld();
    }

    @Override
    public List<DamagePart> damageParts() {
        Level level = getWorld();
        AABB part = box.get();
        // On a ship: through its pose to and from the world.
        SubLevel ship = Sable.HELPER.getContaining(level, getPos());
        Vec3 center = ship != null ? ship.logicalPose().transformPosition(part.getCenter()) : part.getCenter();
        return List.of(new DamagePart(getPos(), DamagePart.PartKind.MECHANISM, part, center,
                world -> ship != null ? ship.logicalPose().transformPositionInverse(world) : world));
    }

    /** Its one part, while anything is wrong (seized included). */
    @Override
    public List<DamagePart> faultyParts() {
        return faults.isEmpty() ? List.of() : damageParts();
    }

    @Override
    public void onBlast(DamagePart part, double severity) {
        if (BattleDamage.radiiFromCenter(severity) <= CDCConfig.server().battleDamage.breakDistance.getF())
            breakDown();
        else
            jostle();
    }

    @Override
    public void onHit(DamagePart part) {
        breakDown();
    }

    // --- Goggles ---

    /** Gold once low enough to seize. */
    private void addLubricationToGoggleTooltip(List<Component> tooltip) {
        if (!isLubricated() || !CDCConfig.server().maintenance.lubrication.allowWearAndSeizing.get())
            return;
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.equipment.lubrication", Math.round(lubrication * 100)))
                .style(lubrication < CDCConfig.server().maintenance.lubrication.seizeBelow.getF() ? ChatFormatting.GOLD : ChatFormatting.GRAY)
                .forGoggles(tooltip);
        // Shown like any Create fluid container, once it holds oil.
        if (!reservoir.isEmpty() && blockEntity instanceof IHaveGoggleInformation goggles)
            goggles.containedFluidTooltip(tooltip, false, getReservoir());
    }

    /** Also says so when nothing is wrong. */
    public void addConditionToGoggleTooltip(List<Component> tooltip) {
        if (faults.isEmpty()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.equipment.working_order"))
                    .style(ChatFormatting.GREEN)
                    .forGoggles(tooltip);
            addLubricationToGoggleTooltip(tooltip);
        } else {
            addToGoggleTooltip(tooltip);
        }
    }

    public void addToGoggleTooltip(List<Component> tooltip) {
        addLubricationToGoggleTooltip(tooltip);
        // Each message has a line break before the repair hint: a goggles line each.
        for (Fault fault : faults)
            for (String line : Component.translatable(fault.translationKey()).getString().split("\n"))
                CDCLang.builder()
                        .add(Component.literal(line.trim()))
                        .style(fault.isOutOfOrder() ? ChatFormatting.RED : ChatFormatting.GOLD)
                        .forGoggles(tooltip);
    }

    // --- Saving ---

    @Override
    public void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(nbt, registries, clientPacket);
        CompoundTag tag = new CompoundTag();
        int bits = 0;
        for (Fault fault : faults)
            bits |= 1 << fault.ordinal();
        tag.putInt("Faults", bits);
        tag.putFloat("BiasYaw", biasYaw);
        tag.putFloat("BiasPitch", biasPitch);
        tag.putFloat("Lubrication", lubrication);
        tag.put("Reservoir", reservoir.writeToNBT(registries, new CompoundTag()));
        nbt.put("Health", tag);
    }

    @Override
    public void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(nbt, registries, clientPacket);
        CompoundTag tag = nbt.getCompound("Health");
        faults.clear();
        int bits = tag.getInt("Faults");
        for (Fault fault : Fault.values())
            if ((bits & 1 << fault.ordinal()) != 0)
                faults.add(fault);
        biasYaw = tag.getFloat("BiasYaw");
        biasPitch = tag.getFloat("BiasPitch");
        lubrication = tag.contains("Lubrication") ? tag.getFloat("Lubrication") : 1;
        reservoir.readFromNBT(registries, tag.getCompound("Reservoir"));
    }

    @Nullable
    public static EquipmentHealth at(Level level, BlockPos pos) {
        return of(level.getBlockEntity(pos));
    }
}