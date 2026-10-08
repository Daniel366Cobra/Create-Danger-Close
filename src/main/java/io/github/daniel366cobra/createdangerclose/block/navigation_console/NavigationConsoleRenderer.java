package io.github.daniel366cobra.createdangerclose.block.navigation_console;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import io.github.daniel366cobra.createdangerclose.client.RenderLight;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Navball, altimeter and speedometer. Model pixels, face toward north; the deck is y = 16. */
public class NavigationConsoleRenderer extends SafeBlockEntityRenderer<NavigationConsoleBlockEntity> {

    private static final float PX = 1 / 16f;

    // Navball: stays fixed to the world, as an attitude indicator.
    private static final float BALL_CENTER_X = 10, BALL_CENTER_Y = 17.51777f, BALL_CENTER_Z = 10.56066f;
    // Altimeter: the needle slides 3 px up its 45° gauge from bedrock to build height.
    private static final float ALTIMETER_TRAVEL_PX = 3;
    private static final float ALTIMETER_SLOPE = (float) Math.sqrt(0.5);
    // Speedometer: vertical axis through (8, 15.75, 3), half a turn clockwise to full scale.
    private static final float SPEEDO_PIVOT_X = 8, SPEEDO_PIVOT_Z = 3;
    private static final float SPEEDO_FULL_DEG = 180;

    public NavigationConsoleRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    protected void renderSafe(NavigationConsoleBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction front = state.getValue(NavigationConsoleBlock.FACING);
        // Light by the space above.
        int deckLight = RenderLight.at(be.getLevel(), be.getBlockPos().above());
        float faceTurn = (float) Math.toRadians(-(front.toYRot() + 180));
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        // Attitude is yaw, then pitch nose-up about the ship's X, then roll about its Z. The ball turns by
        // the inverse, after undoing the console's facing on the ship.
        part(CDCPartialModels.NAV_CONSOLE_BALL, state, faceTurn)
                .translate(BALL_CENTER_X * PX, BALL_CENTER_Y * PX, BALL_CENTER_Z * PX)
                .rotate(-faceTurn, Direction.UP)
                .rotate(rad(-be.getBallRoll(partialTicks)), Direction.SOUTH)
                .rotate(rad(be.getBallPitch(partialTicks)), Direction.EAST)
                .rotate(rad(be.getBallHeading(partialTicks)), Direction.UP)
                .translate(-BALL_CENTER_X * PX, -BALL_CENTER_Y * PX, -BALL_CENTER_Z * PX)
                .light(deckLight)
                .renderInto(ms, vb);

        float climb = ALTIMETER_TRAVEL_PX * PX * ALTIMETER_SLOPE * be.getAltimeter(partialTicks);
        part(CDCPartialModels.NAV_CONSOLE_ALTIMETER_NEEDLE, state, faceTurn)
                .translate(0, climb, climb)
                .light(deckLight)
                .renderInto(ms, vb);

        part(CDCPartialModels.NAV_CONSOLE_SPEEDOMETER_NEEDLE, state, faceTurn)
                .translate(SPEEDO_PIVOT_X * PX, 0, SPEEDO_PIVOT_Z * PX)
                // Counterclockwise from above.
                .rotate(rad(-SPEEDO_FULL_DEG * be.getSpeedometer(partialTicks)), Direction.UP)
                .translate(-SPEEDO_PIVOT_X * PX, 0, -SPEEDO_PIVOT_Z * PX)
                .light(deckLight)
                .renderInto(ms, vb);
    }

    /** The ball reaches 3 px above the block. */
    @Override
    public AABB getRenderBoundingBox(NavigationConsoleBlockEntity be) {
        return new AABB(be.getBlockPos()).expandTowards(0, 4 * PX, 0);
    }

    /** Transforms after this are in model space. */
    private static SuperByteBuffer part(PartialModel partial, BlockState state, float faceTurn) {
        return CachedBuffers.partial(partial, state).rotateCentered(faceTurn, Direction.UP);
    }

    private static float rad(double degrees) {
        return (float) Math.toRadians(degrees);
    }
}