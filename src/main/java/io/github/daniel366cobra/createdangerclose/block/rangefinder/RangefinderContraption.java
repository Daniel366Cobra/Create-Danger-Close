package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import io.github.daniel366cobra.createdangerclose.registries.CDCContraptionTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The rangefinder tube assembled on a {@link RangefinderBearingBlockEntity}.
 *
 * <p>Extends {@link BearingContraption} so Create's controlled contraption
 * entity treats it as a bearing contraption (rotation axis, anchoring,
 * stabilization). It only ever turns in yaw: elevation is not modelled
 * physically, the optics are assumed to have elevating prisms.
 *
 * <p>A valid tube has exactly two {@link RangefinderLensBlock lenses}, looking
 * the same way, side by side on a horizontal bar across their line of sight,
 * and equally far either side of the bearing axis. Their spacing is the
 * baseline. It also has exactly one {@link RangefinderEyepieceBlock eyepiece},
 * looking the same way and centred on the bearing axis, which is the
 * operator's seat.
 */
public class RangefinderContraption extends BearingContraption {

    private int baseline;
    @Nullable private Direction lensFacing;
    @Nullable private BlockPos eyepiecePos;

    public RangefinderContraption() {}

    public RangefinderContraption(Direction facing) {
        super(false, facing);
    }

    @Override
    public ContraptionType getType() {
        return CDCContraptionTypes.RANGEFINDER.value();
    }

    @Override
    public boolean assemble(Level world, BlockPos pos) throws AssemblyException {
        if (!super.assemble(world, pos))
            return false;
        validateLenses();
        validateEyepiece();
        return true;
    }

    /** The eyepiece doubles as a Create contraption seat for the operator. */
    @Override
    public void addBlock(Level level, BlockPos pos, Pair<StructureBlockInfo, BlockEntity> capture) {
        super.addBlock(level, pos, capture);
        if (capture.getKey().state().getBlock() instanceof RangefinderEyepieceBlock)
            getSeats().add(toLocalPos(pos));
    }

    /**
     * Local positions are relative to the anchor (the block next to the
     * bearing), so the bearing axis is local x = 0, z = 0.
     */
    private void validateLenses() throws AssemblyException {
        List<BlockPos> lenses = positionsOf(RangefinderLensBlock.class);

        if (lenses.size() != 2)
            throw fail("lens_count", lenses.size());

        BlockPos a = lenses.get(0);
        BlockPos b = lenses.get(1);
        Direction facingA = getBlocks().get(a).state().getValue(RangefinderLensBlock.FACING);
        Direction facingB = getBlocks().get(b).state().getValue(RangefinderLensBlock.FACING);
        if (facingA != facingB)
            throw fail("lens_facing");

        // Side by side across the line of sight: same height, same depth.
        if (a.getY() != b.getY() || along(a, facingA) != along(b, facingA))
            throw fail("lens_abreast");

        // Equal shoulders: the bar's midpoint is on the bearing axis.
        Direction lateral = facingA.getClockWise();
        int shoulderA = along(a, lateral);
        int shoulderB = along(b, lateral);
        if (shoulderA + shoulderB != 0)
            throw fail("lens_asymmetric", Math.abs(shoulderA), Math.abs(shoulderB));

        baseline = Math.abs(shoulderA - shoulderB);
        lensFacing = facingA;
    }

    /** Runs after {@link #validateLenses()}, so {@link #lensFacing} is set. */
    private void validateEyepiece() throws AssemblyException {
        List<BlockPos> eyepieces = positionsOf(RangefinderEyepieceBlock.class);
        if (eyepieces.size() != 1)
            throw fail("eyepiece_count", eyepieces.size());

        BlockPos eyepiece = eyepieces.get(0);
        if (getBlocks().get(eyepiece).state().getValue(RangefinderEyepieceBlock.FACING) != lensFacing)
            throw fail("eyepiece_facing");

        // Centred on the bearing axis, i.e. midway between the lenses.
        if (along(eyepiece, lensFacing.getClockWise()) != 0)
            throw fail("eyepiece_offcenter");

        eyepiecePos = eyepiece;
    }

    private List<BlockPos> positionsOf(Class<? extends Block> blockClass) {
        return getBlocks().entrySet().stream()
                .filter(e -> blockClass.isInstance(e.getValue().state().getBlock()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static int along(BlockPos pos, Direction direction) {
        return pos.getX() * direction.getStepX()
                + pos.getY() * direction.getStepY()
                + pos.getZ() * direction.getStepZ();
    }

    private static AssemblyException fail(String key, Object... args) {
        return new AssemblyException(Component.translatable("createdangerclose.rangefinder_bearing." + key, args));
    }

    /** Distance between the two lenses, in blocks. */
    public int getBaseline() {
        return baseline;
    }

    /** Line of sight in contraption-local (unrotated) coordinates. */
    @Nullable
    public Direction getLensFacing() {
        return lensFacing;
    }

    /** Local position of the eyepiece, which is also the operator's seat. */
    @Nullable
    public BlockPos getEyepiecePos() {
        return eyepiecePos;
    }

    @Override
    public CompoundTag writeNBT(HolderLookup.Provider registries, boolean spawnPacket) {
        CompoundTag tag = super.writeNBT(registries, spawnPacket);
        tag.putInt("Baseline", baseline);
        if (lensFacing != null)
            tag.putInt("LensFacing", lensFacing.get3DDataValue());
        if (eyepiecePos != null)
            tag.putLong("Eyepiece", eyepiecePos.asLong());
        return tag;
    }

    @Override
    public void readNBT(Level world, CompoundTag tag, boolean spawnData) {
        baseline = tag.getInt("Baseline");
        lensFacing = tag.contains("LensFacing") ? Direction.from3DDataValue(tag.getInt("LensFacing")) : null;
        eyepiecePos = tag.contains("Eyepiece") ? BlockPos.of(tag.getLong("Eyepiece")) : null;
        super.readNBT(world, tag, spawnData);
    }
}
