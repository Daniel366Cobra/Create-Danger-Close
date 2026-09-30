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
 * The operator's station on a rangefinder tube. Once the tube is assembled,
 * right-clicking the eyepiece seats the player there, and the tube follows
 * where they look. Must face the same way as the lenses, centred on the
 * bearing axis.
 *
 * <p>It is a wall-mounted block, like a lever: a plate bolted to the block in
 * front of the operator, with the eyepieces and the seat hanging off it.
 * {@link #FACING} is the line of sight, so the supporting block is the one
 * {@code FACING} points at. Without it the eyepiece pops off.
 */
public class RangefinderEyepieceBlock extends RangefinderOpticBlock {

    public static final MapCodec<RangefinderEyepieceBlock> CODEC = simpleCodec(RangefinderEyepieceBlock::new);

    // The model's solid parts, for an eyepiece facing north (pixels): the
    // plate against the support, the seat mounting beam, and the seat itself.
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

    /** The direction of the block the plate is bolted to: the way the eyepiece looks. */
    public static Direction getSupportDirection(BlockState state) {
        return state.getValue(FACING);
    }

    /**
     * Mounts on the side face that was clicked, looking into that block. If
     * that does not work (a top or bottom face was clicked, or there is
     * nothing to bolt to), tries the blocks the player is looking towards.
     * Null, i.e. not placeable, without any support.
     */
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

    /** The face the plate is bolted to must be solid (a full, sturdy face), as for a lever. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction support = getSupportDirection(state);
        BlockPos supportPos = pos.relative(support);
        return level.getBlockState(supportPos).isFaceSturdy(level, supportPos, support.getOpposite());
    }

    /** Pops off (dropping itself) when the block it is bolted to goes. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == getSupportDirection(state) && !state.canSurvive(level, pos))
            return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /** Turning it would tear it off its support, so the wrench does not turn it. */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        return originalState;
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
