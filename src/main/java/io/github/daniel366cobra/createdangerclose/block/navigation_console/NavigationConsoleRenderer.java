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

/**
 * The Navigation Console's instruments: the navball, the altimeter and the
 * speedometer. Positions are model pixels with the face towards north, as in
 * the model; the deck is the block's top (y = 16).
 */
public class NavigationConsoleRenderer extends SafeBlockEntityRenderer<NavigationConsoleBlockEntity> {

    private static final float PX = 1 / 16f;

    // Navball: turns about its centre so it stays fixed to the world (sky up,
    // north to the north), as an attitude indicator does, while the ship moves round it.
    private static final float BALL_CENTER_X = 10, BALL_CENTER_Y = 17.51777f, BALL_CENTER_Z = 10.56066f;
    // Altimeter: the needle slides 3 px up its 45° gauge, towards the back, from
    // bedrock (as modelled) to build height.
    private static final float ALTIMETER_TRAVEL_PX = 3;
    private static final float ALTIMETER_SLOPE = (float) Math.sqrt(0.5);
    // Speedometer: the needle turns about the vertical axis through (8, 15.75, 3),
    // clockwise seen from above, half a turn from stopped (as modelled) to full scale.
    private static final float SPEEDO_PIVOT_X = 8, SPEEDO_PIVOT_Z = 3;
    private static final float SPEEDO_FULL_DEG = 180;

    public NavigationConsoleRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    protected void renderSafe(NavigationConsoleBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction front = state.getValue(NavigationConsoleBlock.FACING);
        // The instruments stand on the block's top: light them by the space above it.
        int deckLight = RenderLight.at(be.getLevel(), be.getBlockPos().above());
        // The model faces north; the blockstate turns it like this.
        float faceTurn = (float) Math.toRadians(-(front.toYRot() + 180));
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        // The ship's attitude as a rotation from the world into the ship:
        // yaw about the vertical (Minecraft yaw: the ship's +Z turns clockwise
        // from south), then pitch nose-up about its own X, then roll about its
        // own Z. The ball turns by the inverse to stay put in the world: roll
        // back, pitch back, yaw back, after first undoing the console's own
        // facing on the ship.
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
                // About +Y: positive is anticlockwise seen from above.
                .rotate(rad(-SPEEDO_FULL_DEG * be.getSpeedometer(partialTicks)), Direction.UP)
                .translate(-SPEEDO_PIVOT_X * PX, 0, -SPEEDO_PIVOT_Z * PX)
                .light(deckLight)
                .renderInto(ms, vb);
    }

    /** The instruments stand on the block's top, above its own space (the ball reaches 3 px up). */
    @Override
    public AABB getRenderBoundingBox(NavigationConsoleBlockEntity be) {
        return new AABB(be.getBlockPos()).expandTowards(0, 4 * PX, 0);
    }

    /** A partial, turned to the block's facing; transforms after this are in model space. */
    private static SuperByteBuffer part(PartialModel partial, BlockState state, float faceTurn) {
        return CachedBuffers.partial(partial, state).rotateCentered(faceTurn, Direction.UP);
    }

    private static float rad(double degrees) {
        return (float) Math.toRadians(degrees);
    }
}
