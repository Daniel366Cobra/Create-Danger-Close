package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

import com.mojang.serialization.MapCodec;
import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * A module of the fire control computer: a plain block that adds one
 * capability to the Ballistic Computer whose bank it touches (directly, or
 * through other modules). It has no shaft and no block entity; the computer
 * finds it by scanning the bank.
 */
public class ComputerModuleBlock extends HorizontalFacedBlock {

    private final ComputerModule module;
    private final MapCodec<ComputerModuleBlock> codec;

    public ComputerModuleBlock(Properties properties, ComputerModule module) {
        super(properties);
        this.module = module;
        this.codec = simpleCodec(p -> new ComputerModuleBlock(p, module));
    }

    public ComputerModule getModule() {
        return module;
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return codec;
    }
}
