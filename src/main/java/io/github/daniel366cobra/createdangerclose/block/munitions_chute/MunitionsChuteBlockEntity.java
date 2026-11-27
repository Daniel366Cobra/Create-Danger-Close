package io.github.daniel366cobra.createdangerclose.block.munitions_chute;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.logistics.crate.CreativeCrateBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonReading;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.SensorReading;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.index.CBCBlocks;
import rbasamoyai.createbigcannons.index.CBCDataComponents;
import rbasamoyai.createbigcannons.index.CBCItems;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonProjectilePropertiesComponent;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlockItem;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock.HORIZONTAL_AXIS;

/**
 * Stages one round, a shell and a filled cartridge, from the storage on top and on its shaftless sides.
 * Over a belt or depot it sends the round down; otherwise a Mechanical Arm takes it.
 *
 * <p>On a servo's cannon side, or on a battle network near the gun it feeds, the fire control computer
 * reads what it can stage ({@link SensorReading.Munitions}) and tells it which shell the current fire
 * mission calls for ({@link #setRequestedShell}). Requested, it stages only that shell, and nothing at
 * all when no filled cartridge meeting the shell's minimum charge is at hand.
 */
public class MunitionsChuteBlockEntity extends KineticBlockEntity implements SensorReading.Provider {

    public static final int SHELL = 0, CARTRIDGE = 1;
    // Ticks at 1 RPM.
    private static final float INDEX_TICKS_PER_RPM = 1280;
    private static final int MIN_INDEX_TICKS = 5, MAX_INDEX_TICKS = 200;
    // Storage is rescanned this often while no round can be staged.
    private static final int RESCAN_TICKS = 10;
    // A request older than this (its computer gone) reverts to staging whatever comes first.
    private static final long REQUEST_TIMEOUT_TICKS = 100;

    // Saved by ordinal: add new states at the end.
    public enum State { NOT_ROTATING, NO_STORAGE, NO_SHELL, NO_CHARGE, INDEXING, READY, OUTPUT_BLOCKED, NOT_PROVIDED, HOLDING, SURPLUS }

    private final ItemStackHandler slots = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            sendData();
        }
    };
    // What the arm sees: take only.
    private final IItemHandler armView = new IItemHandler() {
        @Override public int getSlots() { return slots.getSlots(); }
        @Override public ItemStack getStackInSlot(int slot) { return slots.getStackInSlot(slot); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return slots.extractItem(slot, amount, simulate); }
        @Override public int getSlotLimit(int slot) { return 1; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    };

    private final Map<Direction, BlockCapabilityCache<IItemHandler, Direction>> storageCaches = new EnumMap<>(Direction.class);
    private State state = State.NOT_ROTATING;
    private boolean beltBelow;
    private int indexTicks;
    private int rescanCooldown;
    // What the last scan found: shell type to the strongest filled cartridge power pairable with it.
    private Map<ResourceLocation, Float> available = Map.of();
    // The shell the fire control computer asked for; stale requests are ignored.
    @Nullable private ResourceLocation requestedShell;
    private long requestedTick;
    // Shells of the requested type this order allows in all, fired ones included: -1 = not
    // mission-bound (stage on). Constant while the gun fires, so a late relay cannot miscount.
    private int requestedRounds = -1;
    // The mission order this request belongs to: a new one re-arms the staged count.
    private int requestSerial = -1;
    // Shells staged (and not returned) for the current order and type, held against the allowance.
    private int stagedForRequest;
    // The servo output whose cannon side train this chute rides; stale entries are dropped.
    @Nullable private BlockPos controllerPos;
    private long controllerTick;
    // Last told it reaches only a cannon another chute supplies (one chute per cannon): it holds.
    // Half the minimum: the freshness check subtracts it from the game time.
    private long surplusTick = Long.MIN_VALUE / 2;
    // Server-evaluated freshness of the controller link, synced for the goggles.
    private boolean controlled;
    // Synced for the goggles (the live fields are server-only).
    @Nullable private ResourceLocation shownRequested;
    private int shownAvailableSize;

    // TEMPORARY diagnostic (investigating "No shell in storage" on a servo-tapped chute): remove after verification.
    private static final boolean DEBUG_SCAN = true;
    private int debugLastScanSize = -1;

    public MunitionsChuteBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public IItemHandler getArmView() {
        return armView;
    }

    public static boolean isShell(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ProjectileBlock<?>;
    }

    public static boolean isFilledCartridge(ItemStack stack) {
        return stack.getItem() instanceof BigCartridgeBlockItem && BigCartridgeBlockItem.getPower(stack) > 0;
    }

    // --- Supply (what the fire control computer reads) ---

    /** What this chute can stage: shell type to the strongest filled cartridge power pairable with it. */
    public Map<ResourceLocation, Float> availableShells() {
        return available;
    }

    /** The shell the current fire mission calls for; null for whatever comes first. */
    public void setRequestedShell(@Nullable ResourceLocation shell, int rounds, int missionSerial, long gameTime) {
        boolean newOrder = missionSerial != requestSerial;
        boolean changed = shell == null ? requestedShell != null : !shell.equals(requestedShell);
        if (newOrder || changed)
            stagedForRequest = 0;
        requestedShell = shell;
        requestedRounds = rounds;
        requestSerial = missionSerial;
        requestedTick = gameTime;
        if (changed)
            setChanged();
    }

    /** The servo output that found this chute on its cannon-side train; re-confirmed each poll. */
    public void setController(@Nullable BlockPos pos, long gameTime) {
        boolean changed = !java.util.Objects.equals(pos, controllerPos);
        // TEMPORARY diagnostic: remove with DEBUG_SCAN after verification.
        if (DEBUG_SCAN && changed)
            CreateDangerClose.LOGGER.info("[CDC chute debug] chute at {} controller {} -> {} (t={})",
                    worldPosition, controllerPos, pos, gameTime);
        controllerPos = pos;
        controllerTick = gameTime;
        if (changed)
            sendData();
    }

    /** Reaches only a cannon another chute already supplies; re-confirmed each poll. */
    public void markSurplus(long gameTime) {
        surplusTick = gameTime;
    }

    private boolean surplus() {
        return level != null && level.getGameTime() - surplusTick <= REQUEST_TIMEOUT_TICKS;
    }

    private boolean freshController() {
        return level != null && controllerPos != null && level.getGameTime() - controllerTick <= REQUEST_TIMEOUT_TICKS;
    }

    @Override
    public SensorReading getSensorReading() {
        return new SensorReading.Munitions(available);
    }

    /** Whether a request from the computer is still fresh (its computer gone: revert to standalone). */
    private boolean requestLive() {
        return level != null && level.getGameTime() - requestedTick <= REQUEST_TIMEOUT_TICKS;
    }

    /** The staged round no longer fits the order (type changed, or the mission is served): back to storage. */
    private boolean stagedWanted() {
        ItemStack staged = slots.getStackInSlot(SHELL);
        if (staged.isEmpty())
            return true;
        if (surplus())
            return false;
        if (!requestLive())
            return true;
        // The staged round is among those counted: over the allowance only if the order shrank.
        if (requestedRounds >= 0 && stagedForRequest > requestedRounds)
            return false;
        return requestedShell == null
                || requestedShell.equals(BuiltInRegistries.ITEM.getKey(staged.getItem()));
    }

    /**
     * Inserts the staged round back into storage; what storage refuses stays staged (and is not sent
     * down). Beside a creative crate the round may be synthesized, so a refused one is dropped instead.
     */
    private void returnStaged() {
        List<IItemHandler> storage = storage();
        boolean creative = creativeCrate();
        boolean moved = false;
        for (int slot = 0; slot < slots.getSlots(); slot++) {
            ItemStack stack = slots.getStackInSlot(slot);
            if (stack.isEmpty())
                continue;
            int before = stack.getCount();
            for (IItemHandler handler : storage)
                for (int s = 0; s < handler.getSlots() && !stack.isEmpty(); s++)
                    stack = handler.insertItem(s, stack, false);
            if (creative)
                stack = ItemStack.EMPTY;
            if (stack.getCount() != before) {
                moved = true;
                // A shell back home was never delivered: it no longer counts against the order.
                if (slot == SHELL && stack.isEmpty())
                    stagedForRequest = Math.max(0, stagedForRequest - 1);
            }
            slots.setStackInSlot(slot, stack);
        }
        if (moved) {
            setChanged();
            sendData();
        }
    }

    // --- Ticking ---

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;

        // Staggered with the block position so a wall of chutes does not scan on one tick.
        if (level.getGameTime() % RESCAN_TICKS == Math.abs(worldPosition.hashCode()) % RESCAN_TICKS)
            scanAvailability();

        // The link's freshness is judged on the server (the tick stamp alone desyncs for the client).
        boolean fresh = freshController();
        if (fresh != controlled) {
            controlled = fresh;
            sendData();
        }

        // A staged round the current order no longer wants goes home before anything else.
        if (!isEmpty() && !stagedWanted())
            returnStaged();

        DirectBeltInputBehaviour below = BlockEntityBehaviour.get(level, worldPosition.below(), DirectBeltInputBehaviour.TYPE);
        boolean belt = below != null && below.canInsertFromSide(Direction.UP);
        State next;
        if (!isEmpty() && !stagedWanted()) {
            // Storage refused it: keep the unwanted round off the loader.
            indexTicks = 0;
            next = surplus() ? State.SURPLUS : State.HOLDING;
        } else if (!isEmpty()) {
            indexTicks = 0;
            // The cartridge waiting on the shell to be taken is not blocked.
            next = belt && !sendDown(below) && !slots.getStackInSlot(SHELL).isEmpty() ? State.OUTPUT_BLOCKED : State.READY;
            // Sent down whole: stage the next one at once.
            if (isEmpty())
                next = stage();
        } else {
            next = stage();
        }
        if (next != state || belt != beltBelow) {
            // TEMPORARY diagnostic: remove with DEBUG_SCAN after verification.
            if (DEBUG_SCAN && next != state
                    && (next == State.NO_SHELL || next == State.NO_STORAGE || next == State.NOT_PROVIDED))
                debugScan(next);
            state = next;
            beltBelow = belt;
            sendData();
        }
    }

    private boolean isEmpty() {
        return slots.getStackInSlot(SHELL).isEmpty() && slots.getStackInSlot(CARTRIDGE).isEmpty();
    }

    /** Shell first, the cartridge once the shell is taken. True when anything moved. */
    private boolean sendDown(DirectBeltInputBehaviour below) {
        int slot = !slots.getStackInSlot(SHELL).isEmpty() ? SHELL : CARTRIDGE;
        ItemStack stack = slots.getStackInSlot(slot);
        if (!below.handleInsertion(stack.copy(), Direction.UP, true).isEmpty())
            return false;
        ItemStack left = below.handleInsertion(stack.copy(), Direction.UP, false);
        slots.setStackInSlot(slot, left);
        return left.isEmpty();
    }

    private State stage() {
        if (getSpeed() == 0) {
            indexTicks = 0;
            return State.NOT_ROTATING;
        }
        boolean waiting = state == State.NO_STORAGE || state == State.NO_SHELL || state == State.NO_CHARGE
                || state == State.NOT_PROVIDED;
        if (waiting && rescanCooldown-- > 0)
            return state;
        rescanCooldown = RESCAN_TICKS;

        if (surplus()) {
            indexTicks = 0;
            return State.SURPLUS;
        }
        boolean live = requestLive();
        // The mission has all it may fire: hold rather than keep feeding the loader.
        if (live && requestedRounds >= 0 && stagedForRequest >= requestedRounds) {
            indexTicks = 0;
            return State.HOLDING;
        }
        ResourceLocation requested = live ? requestedShell : null;
        if (requested != null)
            return stageRequested(requested);

        List<IItemHandler> storage = storage();
        if (storage.isEmpty() && !creativeCrate()) {
            indexTicks = 0;
            return State.NO_STORAGE;
        }
        RoundSource shell = findShell(storage, null);
        // A creative crate stands in for a stocked magazine on the "whatever comes first" path too.
        if (shell == null && creativeCrate()) {
            ResourceLocation type = creativeShells().keySet().stream().sorted().findFirst().orElse(null);
            if (type != null)
                shell = new RoundSource(null, -1, creativeShell(type));
        }
        if (shell == null) {
            indexTicks = 0;
            return State.NO_SHELL;
        }
        // A charge that can fire it (a creative crate's stands in, as in findCartridge).
        ResourceLocation shellType = BuiltInRegistries.ITEM.getKey(shell.peek().getItem());
        RoundSource cartridge = findCartridge(storage, (int) Math.ceil(minChargePower(shellType)));
        if (cartridge == null) {
            indexTicks = 0;
            return State.NO_CHARGE;
        }
        return stageRound(shell, cartridge);
    }

    /** Requested: only that shell, and nothing at all without a charge that can fire it. */
    private State stageRequested(ResourceLocation requested) {
        RoundSource shell = findShell(storage(), requested);
        if (shell == null) {
            indexTicks = 0;
            return State.NOT_PROVIDED;
        }
        float minCharge = minChargePower(requested);
        RoundSource cartridge = findCartridge(storage(), (int) Math.ceil(minCharge));
        if (cartridge == null) {
            indexTicks = 0;
            return State.NOT_PROVIDED;
        }
        return stageRound(shell, cartridge);
    }

    private State stageRound(RoundSource shell, RoundSource cartridge) {
        if (++indexTicks < indexDelay())
            return State.INDEXING;
        indexTicks = 0;
        slots.setStackInSlot(SHELL, shell.extract());
        slots.setStackInSlot(CARTRIDGE, cartridge.extract());
        if (requestLive())
            stagedForRequest++;
        return State.READY;
    }

    private int indexDelay() {
        return Mth.clamp(Math.round(INDEX_TICKS_PER_RPM / Math.abs(getSpeed())), MIN_INDEX_TICKS, MAX_INDEX_TICKS);
    }

    /**
     * A shell that can be pulled: of the requested type if given, else the first. A creative crate
     * on a storage face synthesizes any requested shell, fuzed for impact so it actually detonates.
     */
    @Nullable
    private RoundSource findShell(List<IItemHandler> storage, @Nullable ResourceLocation requested) {
        for (IItemHandler handler : storage) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!isShell(stack))
                    continue;
                ResourceLocation type = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (requested != null && !requested.equals(type))
                    continue;
                if (!handler.extractItem(slot, 1, true).isEmpty())
                    return new RoundSource(handler, slot, null);
            }
        }
        if (requested != null && creativeCrate())
            return new RoundSource(null, -1, creativeShell(requested));
        return null;
    }

    /** The strongest filled cartridge with at least {@code minPower}; null without one. */
    @Nullable
    private RoundSource findCartridge(List<IItemHandler> storage, int minPower) {
        RoundSource best = null;
        int bestPower = -1;
        for (IItemHandler handler : storage) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!isFilledCartridge(stack))
                    continue;
                int power = BigCartridgeBlockItem.getPower(stack);
                if (power < minPower || power <= bestPower)
                    continue;
                if (!handler.extractItem(slot, 1, true).isEmpty()) {
                    best = new RoundSource(handler, slot, null);
                    bestPower = power;
                }
            }
        }
        if (best == null && creativeCrate() && minPower <= maxCartridgePower())
            return new RoundSource(null, -1, BigCartridgeBlockItem.getWithPower(maxCartridgePower()));
        return best;
    }

    /** From storage, else synthesized by a creative crate on a storage face. */
    private record RoundSource(@Nullable IItemHandler handler, int slot, @Nullable ItemStack synthetic) {
        ItemStack extract() {
            return synthetic != null ? synthetic.copy() : handler.extractItem(slot, 1, false);
        }

        ItemStack peek() {
            return synthetic != null ? synthetic : handler.getStackInSlot(slot);
        }
    }

    private static ItemStack creativeShell(ResourceLocation type) {
        Block block = BuiltInRegistries.BLOCK.get(type);
        ItemStack stack = new ItemStack(block.asItem());
        ItemStack fuze = new ItemStack(CBCItems.IMPACT_FUZE.get());
        stack.set(CBCDataComponents.FUZE, ItemContainerContents.fromItems(List.of(fuze)));
        return stack;
    }

    private static int maxCartridgePower() {
        Item item = CBCBlocks.BIG_CARTRIDGE.asItem();
        return item instanceof BigCartridgeBlockItem cartridge ? cartridge.getMaximumPowerLevels() : 4;
    }

    // --- Availability scan ---

    /** Rescanned on a staggered interval: shell types in storage, each paired with the strongest filled cartridge. */
    private void scanAvailability() {
        if (!(level instanceof ServerLevel) || creativeCrate()) {
            if (creativeCrate())
                available = creativeShells();
            return;
        }
        Map<ResourceLocation, Float> shells = new HashMap<>();
        float maxPower = 0;
        for (IItemHandler handler : storage()) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (isShell(stack)) {
                    ResourceLocation type = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    shells.putIfAbsent(type, minChargePower(type));
                } else if (isFilledCartridge(stack)) {
                    maxPower = Math.max(maxPower, BigCartridgeBlockItem.getPower(stack));
                }
            }
        }
        // A shell without a charge that can fire it is not a round the chute can stage.
        float strongest = maxPower;
        shells.values().removeIf(minCharge -> minCharge > strongest);
        for (Map.Entry<ResourceLocation, Float> entry : shells.entrySet())
            entry.setValue(strongest);
        available = Map.copyOf(shells);
        // TEMPORARY diagnostic: remove with DEBUG_SCAN after verification.
        if (DEBUG_SCAN && available.size() != debugLastScanSize) {
            debugLastScanSize = available.size();
            CreateDangerClose.LOGGER.info("[CDC chute debug] chute at {} scan: {} shell types (max cartridge power {})",
                    worldPosition, available.size(), maxPower);
        }
    }

    /** TEMPORARY diagnostic: what the chute sees on each storage face. Remove after verification. */
    private void debugScan(State next) {
        StringBuilder faces = new StringBuilder();
        Direction.Axis open = getBlockState().getValue(HORIZONTAL_AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        for (Direction face : new Direction[]{Direction.UP,
                Direction.fromAxisAndDirection(open, Direction.AxisDirection.POSITIVE),
                Direction.fromAxisAndDirection(open, Direction.AxisDirection.NEGATIVE)}) {
            BlockPos pos = worldPosition.relative(face);
            boolean belt = BlockEntityBehaviour.get(level, pos, DirectBeltInputBehaviour.TYPE) != null;
            IItemHandler handler = level instanceof ServerLevel server
                    ? server.getCapability(Capabilities.ItemHandler.BLOCK, pos, face.getOpposite()) : null;
            List<String> items = new ArrayList<>();
            if (handler != null)
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    if (!stack.isEmpty())
                        items.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount()
                                + (isShell(stack) ? "[shell]" : isFilledCartridge(stack) ? "[cartridge]" : ""));
                }
            faces.append(String.format(" %s=%s%s:%s;", face, level != null ? level.getBlockState(pos).getBlock() : "?", belt ? "(belt,skipped)" : "",
                    handler == null ? "no-handler" : items.isEmpty() ? "empty" : items));
        }
        CreateDangerClose.LOGGER.info("[CDC chute debug] chute at {} entered {} on cannon side of {}:{}", worldPosition, next,
                controllerPos, faces);
    }

    /** Every big cannon shell type: a creative crate stands in for a stocked magazine. */
    private static Map<ResourceLocation, Float> creativeShells() {        Map<ResourceLocation, Float> shells = new HashMap<>();
        for (ResourceLocation key : BuiltInRegistries.BLOCK.keySet()) {
            Block block = BuiltInRegistries.BLOCK.get(key);
            if (block instanceof ProjectileBlock<?> && block instanceof BigCannonMunitionBlock)
                shells.put(key, Float.MAX_VALUE);
        }
        return Map.copyOf(shells);
    }

    /** A shell item's datapack minimum charge power; 0 if it has no entry. */
    private static float minChargePower(ResourceLocation type) {
        if (!(BuiltInRegistries.BLOCK.get(type) instanceof ProjectileBlock<?> projectile))
            return 0;
        EntityType<?> entityType = projectile.getAssociatedEntityType();
        BigCannonProjectilePropertiesComponent properties = CannonReading.resolveBigCannonProperties(entityType);
        return properties.minimumChargePower();
    }

    // --- Storage faces ---

    /** Top first, then the open sides. Transport blocks are not storage. */
    private List<IItemHandler> storage() {
        List<IItemHandler> found = new ArrayList<>();
        if (!(level instanceof ServerLevel server))
            return found;
        Direction.Axis open = getBlockState().getValue(HORIZONTAL_AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        Direction[] faces = {Direction.UP,
                Direction.fromAxisAndDirection(open, Direction.AxisDirection.POSITIVE),
                Direction.fromAxisAndDirection(open, Direction.AxisDirection.NEGATIVE)};
        for (Direction face : faces) {
            BlockPos pos = worldPosition.relative(face);
            if (BlockEntityBehaviour.get(level, pos, DirectBeltInputBehaviour.TYPE) != null)
                continue;
            IItemHandler handler = storageCaches
                    .computeIfAbsent(face, f -> BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, server, pos, f.getOpposite()))
                    .getCapability();
            if (handler != null)
                found.add(handler);
        }
        return found;
    }

    /** A creative crate on a storage face provides every shell, fuzed and charged on demand. */
    private boolean creativeCrate() {
        if (level == null)
            return false;
        Direction.Axis open = getBlockState().getValue(HORIZONTAL_AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        for (Direction face : new Direction[]{Direction.UP,
                Direction.fromAxisAndDirection(open, Direction.AxisDirection.POSITIVE),
                Direction.fromAxisAndDirection(open, Direction.AxisDirection.NEGATIVE)}) {
            if (level.getBlockEntity(worldPosition.relative(face)) instanceof CreativeCrateBlockEntity)
                return true;
        }
        return false;
    }

    @Override
    public void destroy() {
        super.destroy();
        if (level == null)
            return;
        for (int slot = 0; slot < slots.getSlots(); slot++)
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), slots.getStackInSlot(slot));
    }

    // --- Goggles ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.munitions_chute.title"))
                .forGoggles(tooltip);
        Component control = Component.translatable("createdangerclose.munitions_chute.standalone");
        if (controlled && controllerPos != null && level.getBlockEntity(controllerPos) != null)
            control = level.getBlockState(controllerPos).getBlock().getName();
        line(tooltip, Component.translatable("createdangerclose.munitions_chute.control", control), ChatFormatting.GRAY);
        line(tooltip, Component.translatable("createdangerclose.munitions_chute.output",
                Component.translatable(beltBelow ? "createdangerclose.munitions_chute.output_belt"
                        : "createdangerclose.munitions_chute.output_arm")), ChatFormatting.GRAY);

        ItemStack shell = slots.getStackInSlot(SHELL);
        line(tooltip, Component.translatable("createdangerclose.munitions_chute.shell",
                shell.isEmpty() ? Component.translatable("createdangerclose.munitions_chute.none") : shell.getHoverName()),
                ChatFormatting.GRAY);
        ItemStack cartridge = slots.getStackInSlot(CARTRIDGE);
        line(tooltip, Component.translatable("createdangerclose.munitions_chute.charge",
                cartridge.isEmpty() ? Component.translatable("createdangerclose.munitions_chute.none")
                        : Component.translatable("createdangerclose.munitions_chute.cartridge",
                        cartridge.getHoverName(), BigCartridgeBlockItem.getPower(cartridge))),
                ChatFormatting.GRAY);

        ResourceLocation requested = shownRequested;
        if (requested != null)
            line(tooltip, Component.translatable("createdangerclose.munitions_chute.requested", shellName(requested)),
                    ChatFormatting.YELLOW);
        if (shownAvailableSize > 0)
            line(tooltip, Component.translatable("createdangerclose.munitions_chute.available", shownAvailableSize),
                    ChatFormatting.GRAY);

        ChatFormatting style = switch (state) {
            case READY -> ChatFormatting.GREEN;
            case INDEXING -> ChatFormatting.GRAY;
            case NOT_ROTATING -> ChatFormatting.DARK_GRAY;
            case NO_SHELL, NO_CHARGE, NOT_PROVIDED -> ChatFormatting.GOLD;
            case NO_STORAGE, OUTPUT_BLOCKED -> ChatFormatting.RED;
            case HOLDING -> ChatFormatting.BLUE;
            case SURPLUS -> ChatFormatting.GOLD;
        };
        line(tooltip, Component.translatable("createdangerclose.munitions_chute.state." + state.name().toLowerCase()), style);

        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }

    private static Component shellName(ResourceLocation type) {
        return BuiltInRegistries.BLOCK.containsKey(type)
                ? BuiltInRegistries.BLOCK.get(type).getName()
                : Component.literal(type.toString());
    }

    private static void line(List<Component> tooltip, Component text, ChatFormatting style) {
        CDCLang.builder().add(text).style(style).forGoggles(tooltip);
    }

    // --- Saving ---

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.put("Round", slots.serializeNBT(registries));
        compound.putInt("State", state.ordinal());
        compound.putBoolean("BeltBelow", beltBelow);
        compound.putBoolean("Controlled", controlled);
        compound.putInt("Avail", available.size());
        ResourceLocation req = requestLive() ? requestedShell : null;
        if (req != null)
            compound.putString("Requested", req.toString());
        if (controllerPos != null) {
            compound.putLong("Controller", controllerPos.asLong());
            compound.putLong("ControllerTick", controllerTick);
        }
        if (!clientPacket)
            compound.putInt("IndexTicks", indexTicks);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        slots.deserializeNBT(registries, compound.getCompound("Round"));
        State[] states = State.values();
        state = states[Mth.clamp(compound.getInt("State"), 0, states.length - 1)];
        beltBelow = compound.getBoolean("BeltBelow");
        controlled = compound.getBoolean("Controlled");
        shownAvailableSize = compound.getInt("Avail");
        shownRequested = compound.contains("Requested")
                ? ResourceLocation.tryParse(compound.getString("Requested")) : null;
        controllerPos = compound.contains("Controller") ? BlockPos.of(compound.getLong("Controller")) : null;
        controllerTick = compound.getLong("ControllerTick");
        if (!clientPacket)
            indexTicks = compound.getInt("IndexTicks");
    }
}
