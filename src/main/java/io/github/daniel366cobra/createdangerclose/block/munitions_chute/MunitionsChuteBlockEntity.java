package io.github.daniel366cobra.createdangerclose.block.munitions_chute;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlockItem;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock.HORIZONTAL_AXIS;

/**
 * Stages one round, a shell and a filled cartridge, from the storage on top and on its shaftless sides.
 * Over a belt or depot it sends the round down; otherwise a Mechanical Arm takes it.
 */
public class MunitionsChuteBlockEntity extends KineticBlockEntity {

    public static final int SHELL = 0, CARTRIDGE = 1;
    // Ticks at 1 RPM.
    private static final float INDEX_TICKS_PER_RPM = 1280;
    private static final int MIN_INDEX_TICKS = 5, MAX_INDEX_TICKS = 200;
    // Storage is rescanned this often while no round can be staged.
    private static final int RESCAN_TICKS = 10;

    public enum State { NOT_ROTATING, NO_STORAGE, NO_SHELL, NO_CHARGE, INDEXING, READY, OUTPUT_BLOCKED }

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

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;

        DirectBeltInputBehaviour below = BlockEntityBehaviour.get(level, worldPosition.below(), DirectBeltInputBehaviour.TYPE);
        boolean belt = below != null && below.canInsertFromSide(Direction.UP);
        State next;
        if (!isEmpty()) {
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
        boolean waiting = state == State.NO_STORAGE || state == State.NO_SHELL || state == State.NO_CHARGE;
        if (waiting && rescanCooldown-- > 0)
            return state;
        rescanCooldown = RESCAN_TICKS;

        List<IItemHandler> storage = storage();
        if (storage.isEmpty()) {
            indexTicks = 0;
            return State.NO_STORAGE;
        }
        Found shell = find(storage, true);
        if (shell == null) {
            indexTicks = 0;
            return State.NO_SHELL;
        }
        Found cartridge = find(storage, false);
        if (cartridge == null) {
            indexTicks = 0;
            return State.NO_CHARGE;
        }
        if (++indexTicks < indexDelay())
            return State.INDEXING;

        indexTicks = 0;
        ItemStack shellStack = shell.handler.extractItem(shell.slot, 1, false);
        ItemStack cartridgeStack = cartridge.handler.extractItem(cartridge.slot, 1, false);
        slots.setStackInSlot(SHELL, shellStack);
        slots.setStackInSlot(CARTRIDGE, cartridgeStack);
        return State.READY;
    }

    private int indexDelay() {
        return Mth.clamp(Math.round(INDEX_TICKS_PER_RPM / Math.abs(getSpeed())), MIN_INDEX_TICKS, MAX_INDEX_TICKS);
    }

    private record Found(IItemHandler handler, int slot) {}

    // The first shell, or the first filled cartridge, that can be pulled.
    @Nullable
    private static Found find(List<IItemHandler> storage, boolean shell) {
        for (IItemHandler handler : storage) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (shell ? !isShell(stack) : !isFilledCartridge(stack))
                    continue;
                if (!handler.extractItem(slot, 1, true).isEmpty())
                    return new Found(handler, slot);
            }
        }
        return null;
    }

    // Top first, then the open sides. Transport blocks are not storage.
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
        line(tooltip, Component.translatable("createdangerclose.munitions_chute.control",
                Component.translatable("createdangerclose.munitions_chute.standalone")), ChatFormatting.GRAY);
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

        ChatFormatting style = switch (state) {
            case READY -> ChatFormatting.GREEN;
            case INDEXING -> ChatFormatting.GRAY;
            case NOT_ROTATING -> ChatFormatting.DARK_GRAY;
            case NO_SHELL, NO_CHARGE -> ChatFormatting.GOLD;
            case NO_STORAGE, OUTPUT_BLOCKED -> ChatFormatting.RED;
        };
        line(tooltip, Component.translatable("createdangerclose.munitions_chute.state." + state.name().toLowerCase()), style);

        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
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
        if (!clientPacket)
            indexTicks = compound.getInt("IndexTicks");
    }
}