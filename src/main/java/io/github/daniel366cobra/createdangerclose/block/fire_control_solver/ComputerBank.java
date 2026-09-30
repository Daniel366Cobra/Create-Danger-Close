package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * The bank of a fire control computer: a Ballistic Computer and the modules
 * touching it, directly or through other modules, in any shape. Found by
 * following touching faces; nothing is stored in the blocks.
 */
public final class ComputerBank {

    /** Most modules a bank is followed through. */
    public static final int MAX_MODULES = 16;

    /**
     * @param modules the kinds of module in the bank
     * @param cores   the Ballistic Computers touching it; more than one is a conflict
     */
    public record Scan(Set<ComputerModule> modules, Set<BlockPos> cores) {}

    private ComputerBank() {}

    /**
     * Follows the bank from one of its blocks, a Ballistic Computer or a
     * module. Modules chain on to their neighbours; computers do not, other
     * than the one the scan starts at, so two computers only share a bank if
     * their modules touch.
     */
    public static Scan scan(Level level, BlockPos start) {
        Set<ComputerModule> modules = EnumSet.noneOf(ComputerModule.class);
        Set<BlockPos> cores = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(start);
        visited.add(start);
        int moduleCount = 0;

        while (!frontier.isEmpty()) {
            BlockPos pos = frontier.remove();
            BlockState state = level.getBlockState(pos);
            boolean isCore = state.getBlock() instanceof FireControlSolverBlock;
            if (isCore) {
                cores.add(pos);
                if (!pos.equals(start))
                    continue;
            } else if (state.getBlock() instanceof ComputerModuleBlock module) {
                if (++moduleCount > MAX_MODULES)
                    break;
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
        return new Scan(modules, cores);
    }

    /**
     * The Ballistic Computer of the bank a block belongs to, for a network
     * interface facing any block of it. Null if the block is not part of a
     * bank, or the bank has no computer.
     */
    @Nullable
    public static FireControlSolverBlockEntity findComputer(Level level, BlockPos pos) {
        for (BlockPos core : scan(level, pos).cores())
            if (level.getBlockEntity(core) instanceof FireControlSolverBlockEntity computer)
                return computer;
        return null;
    }
}
