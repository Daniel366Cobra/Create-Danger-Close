package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.mojang.serialization.MapCodec;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The operator's station on a rangefinder tube: seats the player, and the tube follows their look.
 * Wall-mounted like a lever: {@link #FACING} is the line of sight and points at its support; without
 * it, it pops off.
 */
public class RangefinderEyepieceBlock extends RangefinderOpticBlock {

    public static final MapCodec<RangefinderEyepieceBlock> CODEC = simpleCodec(RangefinderEyepieceBlock::new);

    // Facing north, pixels: plate, seat beam, seat.
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(Shapes.or(
            Block.box(0, 0, 0, 16, 16, 2),
            Block.box(7, 1, 2, 9, 3, 12),
            Block.box(3, 3, 7, 13, 5, 16)), Direction.NORTH);

    public RangefinderEyepieceBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE.get(state.getValue(FACING));
    }

    /** The way it looks. */
    public static Direction getSupportDirection(BlockState state) {
        return state.getValue(FACING);
    }

    /** On the clicked side face; else toward where the player looks. Null without any support. */
    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clickedFace = context.getClickedFace();
        if (clickedFace.getAxis().isHorizontal()) {
            BlockState state = defaultBlockState().setValue(FACING, clickedFace.getOpposite());
            if (state.canSurvive(context.getLevel(), context.getClickedPos()))
                return state;
        }
        for (Direction looking : context.getNearestLookingDirections()) {
            if (!looking.getAxis().isHorizontal())
                continue;
            BlockState state = defaultBlockState().setValue(FACING, looking);
            if (state.canSurvive(context.getLevel(), context.getClickedPos()))
                return state;
        }
        return null;
    }

    /** A full, sturdy face, as for a lever. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction support = getSupportDirection(state);
        BlockPos supportPos = pos.relative(support);
        return level.getBlockState(supportPos).isFaceSturdy(level, supportPos, support.getOpposite());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == getSupportDirection(state) && !state.canSurvive(level, pos))
            return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /** Turning it would tear it off. */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        return originalState;
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}