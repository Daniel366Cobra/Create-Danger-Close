package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/** A Ballistic Computer and the modules touching it, directly or through modules. Nothing is stored in the blocks. */
public final class ComputerBank {

    public static final int MAX_MODULES = 16;

    /**
     * @param cores   more than one is a conflict
     * @param members its modules and the computers touching them
     */
    public record Scan(Set<ComputerModule> modules, Set<BlockPos> cores, List<BlockPos> members) {}

    private ComputerBank() {}

    /** Modules chain on; other computers do not, so two only share a bank if their modules touch. */
    public static Scan scan(Level level, BlockPos start) {
        Set<ComputerModule> modules = EnumSet.noneOf(ComputerModule.class);
        Set<BlockPos> cores = new HashSet<>();
        List<BlockPos> members = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(start);
        visited.add(start);
        int moduleCount = 0;

        while (!frontier.isEmpty()) {
            BlockPos pos = frontier.remove();
            BlockState state = level.getBlockState(pos);
            boolean isCore = state.getBlock() instanceof BallisticComputerBlock;
            if (isCore) {
                cores.add(pos);
                members.add(pos);
                if (!pos.equals(start))
                    continue;
            } else if (state.getBlock() instanceof ComputerModuleBlock module) {
                if (++moduleCount > MAX_MODULES)
                    break;
                members.add(pos);
                EquipmentHealth health = EquipmentHealth.at(level, pos);
                if (health == null || !health.isOutOfOrder())
                    modules.add(module.getModule());
            } else {
                continue;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (visited.add(next) && level.isLoaded(next))
                    frontier.add(next);
            }
        }
        return new Scan(modules, cores, members);
    }

    /** Every cabinet, and the interface driving the computer. */
    public static List<BlockPos> lubricationGroup(Level level, BlockPos pos) {
        Scan scan = scan(level, pos);
        Set<BlockPos> members = new HashSet<>(scan.members());
        // A scan stops at other computers: add the cabinets on their far side.
        for (BlockPos core : scan.cores())
            members.addAll(scan(level, core).members());
        List<BlockPos> group = new ArrayList<>(members);
        for (BlockPos core : scan.cores())
            if (level.getBlockEntity(core) instanceof BallisticComputerBlockEntity computer
                    && computer.getLinkedInterface() != null)
                group.add(computer.getLinkedInterface());
        return group;
    }

    /** For an interface facing any block of the bank. Null without a bank or computer. */
    @Nullable
    public static BallisticComputerBlockEntity findComputer(Level level, BlockPos pos) {
        for (BlockPos core : scan(level, pos).cores())
            if (level.getBlockEntity(core) instanceof BallisticComputerBlockEntity computer)
                return computer;
        return null;
    }
}