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
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The tube on a {@link RangefinderBearingBlockEntity}; a {@link BearingContraption} for Create's
 * entity. Yaw only: elevation is assumed done by prisms. Two lenses facing the same way, side by side,
 * symmetric about the bearing axis (their spacing is the baseline); one eyepiece on the axis.
 */
public class RangefinderContraption extends BearingContraption {

    // Blocks of the tube itself clear in front of each lens.
    private static final int LENS_CLEARANCE = 16;

    private int baseline;
    @Nullable private Direction lensFacing;
    @Nullable private BlockPos lensA;
    @Nullable private BlockPos lensB;
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

    @Override
    public void addBlock(Level level, BlockPos pos, Pair<StructureBlockInfo, BlockEntity> capture) {
        super.addBlock(level, pos, capture);
        if (capture.getKey().state().getBlock() instanceof RangefinderEyepieceBlock)
            getSeats().add(toLocalPos(pos));
    }

    /** Local positions are relative to the anchor, so the bearing axis is x = 0, z = 0. */
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

        // The sight trace ignores the tube's own blocks, so they must not stand in front of a lens.
        for (BlockPos lens : lenses)
            for (int distance = 1; distance <= LENS_CLEARANCE; distance++)
                if (getBlocks().containsKey(lens.relative(facingA, distance)))
                    throw fail("lens_obstructed", distance);

        baseline = Math.abs(shoulderA - shoulderB);
        lensFacing = facingA;
        lensA = a;
        lensB = b;
    }

    /** Runs after {@link #validateLenses()}, so {@link #lensFacing} is set. */
    private void validateEyepiece() throws AssemblyException {
        List<BlockPos> eyepieces = positionsOf(RangefinderEyepieceBlock.class);
        if (eyepieces.size() != 1)
            throw fail("eyepiece_count", eyepieces.size());

        BlockPos eyepiece = eyepieces.get(0);
        if (getBlocks().get(eyepiece).state().getValue(RangefinderEyepieceBlock.FACING) != lensFacing)
            throw fail("eyepiece_facing");

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

    /** Local positions of the two lenses; empty for tubes assembled before they were stored. */
    public List<BlockPos> getLenses() {
        return lensA != null && lensB != null ? List.of(lensA, lensB) : List.of();
    }

    public boolean isLensCracked(BlockPos lens) {
        StructureBlockInfo info = getBlocks().get(lens);
        return info != null && info.state().getBlock() instanceof RangefinderLensBlock
                && info.state().getValue(RangefinderLensBlock.CRACKED);
    }

    public int getCrackedLensCount() {
        int cracked = 0;
        for (BlockPos lens : getLenses())
            if (isLensCracked(lens))
                cracked++;
        return cracked;
    }

    /** Blocks. */
    public int getBaseline() {
        return baseline;
    }

    /** Local (unrotated). */
    @Nullable
    public Direction getLensFacing() {
        return lensFacing;
    }

    /** Also the operator's seat. */
    @Nullable
    public BlockPos getEyepiecePos() {
        return eyepiecePos;
    }

    // From Create's default seat, model pixels: down, and back from the line of sight.
    private static final double OPERATOR_SEAT_DOWN_PX = 3;
    private static final double OPERATOR_SEAT_BACK_PX = 4;

    /** Local (unrotated), so it turns with the tube. */
    public Vec3 getOperatorSeatOffset() {
        if (lensFacing == null)
            return Vec3.ZERO;
        Vec3 back = Vec3.atLowerCornerOf(lensFacing.getOpposite().getNormal());
        return back.scale(OPERATOR_SEAT_BACK_PX / 16)
                .add(0, -OPERATOR_SEAT_DOWN_PX / 16, 0);
    }

    private static final double OPERATOR_VIEW_FORWARD = 0.5;

    /** Midway between the lens centers, just in front: the optics relay the view from the objectives. Local. */
    @Nullable
    public Vec3 getOperatorViewPoint() {
        if (lensA == null || lensB == null || lensFacing == null)
            return null;
        Vec3 midpoint = Vec3.atCenterOf(lensA).add(Vec3.atCenterOf(lensB)).scale(0.5);
        return midpoint.add(Vec3.atLowerCornerOf(lensFacing.getNormal()).scale(OPERATOR_VIEW_FORWARD));
    }

    @Override
    public CompoundTag writeNBT(HolderLookup.Provider registries, boolean spawnPacket) {
        CompoundTag tag = super.writeNBT(registries, spawnPacket);
        tag.putInt("Baseline", baseline);
        if (lensFacing != null)
            tag.putInt("LensFacing", lensFacing.get3DDataValue());
        if (lensA != null && lensB != null) {
            tag.putLong("LensA", lensA.asLong());
            tag.putLong("LensB", lensB.asLong());
        }
        if (eyepiecePos != null)
            tag.putLong("Eyepiece", eyepiecePos.asLong());
        return tag;
    }

    @Override
    public void readNBT(Level world, CompoundTag tag, boolean spawnData) {
        baseline = tag.getInt("Baseline");
        lensFacing = tag.contains("LensFacing") ? Direction.from3DDataValue(tag.getInt("LensFacing")) : null;
        lensA = tag.contains("LensA") ? BlockPos.of(tag.getLong("LensA")) : null;
        lensB = tag.contains("LensB") ? BlockPos.of(tag.getLong("LensB")) : null;
        eyepiecePos = tag.contains("Eyepiece") ? BlockPos.of(tag.getLong("Eyepiece")) : null;
        super.readNBT(world, tag, spawnData);
    }
}