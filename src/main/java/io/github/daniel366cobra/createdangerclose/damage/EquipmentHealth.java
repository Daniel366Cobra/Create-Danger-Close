package io.github.daniel366cobra.createdangerclose.damage;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.api.damage.BattleDamageable;
import io.github.daniel366cobra.createdangerclose.api.damage.DamagePart;
import io.github.daniel366cobra.createdangerclose.api.damage.Fault;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.config.CServer;
import io.github.daniel366cobra.createdangerclose.registries.CDCFluids;
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
 * The health record of a piece of mechanical equipment, attached to its block
 * entity: what is wrong with it, and the bias a jostle left it with. It saves
 * and syncs itself, takes battle damage for its block (as a
 * {@link BattleDamageable}), and writes its own goggles lines. The equipment
 * only asks it {@link #isOutOfOrder()} and {@link #biasYaw()} / {@link #biasPitch()}.
 */
public class EquipmentHealth extends BlockEntityBehaviour implements BattleDamageable {

    public static final BehaviourType<EquipmentHealth> TYPE = new BehaviourType<>();

    // A jostle throws the equipment off by between this share of the most (config) and the most.
    private static final float MIN_BIAS_SHARE = 0.4f;

    /** What a heavy blow breaks in this equipment. */
    public enum Breaks { COG, MECHANISM }

    private final Breaks breaks;
    private final boolean canJostle;
    private Supplier<AABB> box;

    private final Set<Fault> faults = EnumSet.noneOf(Fault.class);

    // --- Lubrication: only for equipment given its network speed (see lubricated) ---
    // It dries out faster the faster its network turns (linear in RPM, to full speed), to
    // reward powering a ship down and make high readiness cost upkeep (rates: config).
    private static final float TICKS_PER_DAY = 24000;
    private static final float FULL_SPEED_RPM = 256;
    private static final int MAINTENANCE_INTERVAL_TICKS = 20;
    @Nullable private Supplier<Float> networkRpm;
    private BooleanSupplier running = () -> false;
    private Supplier<Collection<BlockPos>> lubricationGroup = () -> List.of(getPos());
    private float lubrication = 1;
    // A small oil reservoir, filled by pipe: it tops up its lubrication group by itself.
    private static final int RESERVOIR_MB = 1000;
    private final FluidTank reservoir = new FluidTank(RESERVOIR_MB, EquipmentHealth::isLubricant) {
        @Override
        protected void onContentsChanged() {
            blockEntity.setChanged();
            reservoirChanged = true;
        }
    };
    // Sent on with the next maintenance tick rather than at every drop a pipe moves.
    private boolean reservoirChanged;
    // The jostle's bias (degrees), while jostled.
    private float biasYaw, biasPitch;

    /**
     * @param breaks    what a close blast or a hit breaks
     * @param canJostle whether a lighter blast jostles it (else it shrugs that off)
     */
    public EquipmentHealth(SmartBlockEntity be, Breaks breaks, boolean canJostle) {
        super(be);
        this.breaks = breaks;
        this.canJostle = canJostle;
        this.box = () -> new AABB(be.getBlockPos());
    }

    /**
     * Gives it lubrication, which its network's speed wears down, and lets it seize
     * when running dry.
     *
     * @param networkRpm its speed on the battle network
     * @param running    whether it is working (powered at operating speed): only then can it seize
     */
    public EquipmentHealth lubricated(Supplier<Float> networkRpm, BooleanSupplier running) {
        this.networkRpm = networkRpm;
        this.running = running;
        return this;
    }

    /** The blocks it shares its lubrication with (itself among them): one honeycomb click serves them all. */
    public EquipmentHealth sharingLubricationWith(Supplier<Collection<BlockPos>> group) {
        this.lubricationGroup = group;
        return this;
    }

    /** Its vulnerable box, in the level (default: its block). */
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

    /** Whether it does not work at all: broken or seized. */
    public boolean isOutOfOrder() {
        for (Fault fault : faults)
            if (fault.isOutOfOrder())
                return true;
        return false;
    }

    public boolean has(Fault fault) {
        return faults.contains(fault);
    }

    /** The jostle's bias in yaw, degrees (0 unless jostled). */
    public float biasYaw() {
        return faults.contains(Fault.JOSTLED) ? biasYaw : 0;
    }

    /** The jostle's bias in pitch, degrees (0 unless jostled). */
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

    /** Clears a fault (a repair). Returns whether it was there. */
    public boolean repair(Fault fault) {
        if (!faults.remove(fault))
            return false;
        changed();
        return true;
    }

    /** Ponder only: sets or clears a fault quietly (no sound, nothing to sync). */
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

    /** Dries a little, by its network's speed; running dry, it may seize. */
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
        // Sent on in whole percent, for the goggles.
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

    /** Whether it takes lubrication (honeycomb). */
    public boolean isLubricated() {
        return networkRpm != null;
    }

    public float getLubrication() {
        return lubrication;
    }

    /**
     * Honeycomb: tops up its whole lubrication group, taking as many combs from the
     * held stack as that needs (one fills one block), shares what there is equally
     * among them, and frees any seized one (that takes a comb at least).
     *
     * @return whether anything was done
     */
    /** The lubricated equipment sharing its lubrication (itself among them). */
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
        return fluid.is(CDCFluids.LUBRICANTS) || fluid.is(CDCFluids.WEAK_LUBRICANTS);
    }

    /** How much of the reservoir's fluid fills one block's lubrication, mB. */
    private int fluidPerBlock() {
        CServer.Lubrication config = CDCConfig.server().maintenance.lubrication;
        return reservoir.getFluid().is(CDCFluids.LUBRICANTS) ? config.lubricantPerBlock.get() : config.plantOilPerBlock.get();
    }

    /**
     * Tops up its group from the reservoir, as far as the oil goes: below full by at least
     * a percent, a member takes what it needs (a seized one is freed by it).
     */
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
            member.faults.remove(Fault.SEIZED);
            member.changed();
            if (reservoir.isEmpty())
                return;
        }
    }

    /** What pipes see: a reservoir that takes lubricants in, and gives nothing back. */
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
        boolean seized = false;
        for (EquipmentHealth member : group) {
            total += member.lubrication;
            seized |= member.faults.contains(Fault.SEIZED);
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
        return true;
    }

    /** A particle on the top of the part, drifting up. */
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
        // On a ship the block stands in the ship's plot: through its pose to and from the world.
        SubLevel ship = Sable.HELPER.getContaining(level, getPos());
        Vec3 centre = ship != null ? ship.logicalPose().transformPosition(part.getCenter()) : part.getCenter();
        return List.of(new DamagePart(getPos(), DamagePart.PartKind.MECHANISM, part, centre,
                world -> ship != null ? ship.logicalPose().transformPositionInverse(world) : world));
    }

    @Override
    public void onBlast(DamagePart part, double severity) {
        if (BattleDamage.radiiFromCentre(severity) <= CDCConfig.server().battleDamage.breakDistance.getF())
            breakDown();
        else
            jostle();
    }

    @Override
    public void onHit(DamagePart part) {
        breakDown();
    }

    // --- Goggles ---

    /** Its lubrication, if it takes any: gold once low enough to seize. */
    private void addLubricationToGoggleTooltip(List<Component> tooltip) {
        if (!isLubricated() || !CDCConfig.server().maintenance.lubrication.allowWearAndSeizing.get())
            return;
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.equipment.lubrication", Math.round(lubrication * 100)))
                .style(lubrication < CDCConfig.server().maintenance.lubrication.seizeBelow.getF() ? ChatFormatting.GOLD : ChatFormatting.GRAY)
                .forGoggles(tooltip);
        // The reservoir, as Create shows any fluid container: only once it holds oil.
        if (!reservoir.isEmpty() && blockEntity instanceof IHaveGoggleInformation goggles)
            goggles.containedFluidTooltip(tooltip, false, getReservoir());
    }

    /** Like {@link #addToGoggleTooltip}, but says so when nothing is wrong. */
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

    /** Adds a line per fault, explaining it and its repair. */
    public void addToGoggleTooltip(List<Component> tooltip) {
        addLubricationToGoggleTooltip(tooltip);
        // The messages carry their own line break before the repair hint: a goggles line each.
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

    /** The block at this position's health record, if it has one. */
    @Nullable
    public static EquipmentHealth at(Level level, BlockPos pos) {
        return of(level.getBlockEntity(pos));
    }
}
