package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import io.github.daniel366cobra.createdangerclose.client.RenderLight;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.PanelText;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The Fire Mission Console's controls and readouts. Positions are model pixels
 * with the face towards north, as in the model; the deck is the block's top (y = 16).
 */
public class FireMissionConsoleRenderer extends SafeBlockEntityRenderer<FireMissionConsoleBlockEntity> {

    private static final float PX = 1 / 16f;

    // Fuze knob: turns about the vertical axis through (12.5, 16, 7.5), up to
    // this far either way; clockwise seen from above is a later burst.
    private static final float KNOB_PIVOT_X = 12.5f, KNOB_PIVOT_Z = 7.5f;
    private static final float KNOB_MAX_DEG = 135;
    // Trajectory lever: tips about the east-west axis through (8.5, 15.75, 7.5),
    // a quarter turn from up (direct, as modelled) to down (plunging), towards the player.
    private static final float LEVER_PIVOT_Y = 15.75f, LEVER_PIVOT_Z = 7.5f;
    private static final float LEVER_DOWN_DEG = -90;
    // Fire button: presses straight down this far.
    private static final float BUTTON_TRAVEL_PX = 0.75f;

    // Readout windows in front of each control, 0.5 px deep: the text lies 0.25 px above their floor.
    private static final float READOUT_FRONT = 2, READOUT_BACK = 4, READOUT_HEIGHT = 15.75f, READOUT_FILL = 0.9f;
    private static final PanelText FUZE_READOUT = PanelText.top(11, 14, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    private static final PanelText TRAJECTORY_READOUT = PanelText.top(7, 10, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    private static final PanelText READY_READOUT = PanelText.top(2, 5, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    // Single digits keep "ready/total" to three characters.
    private static final int MAX_GUNS_SHOWN = 9;

    private final Font font;

    public FireMissionConsoleRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    protected void renderSafe(FireMissionConsoleBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction front = state.getValue(FireMissionConsoleBlock.FACING);
        // The block does not fill its space: light the deck by the space above it.
        int deckLight = RenderLight.at(be.getLevel(), be.getBlockPos().above());
        // The model faces north; the blockstate turns it like this.
        float faceTurn = (float) Math.toRadians(-(front.toYRot() + 180));
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        part(CDCPartialModels.CONSOLE_FUZE_KNOB, state, faceTurn)
                .translate(KNOB_PIVOT_X * PX, 0, KNOB_PIVOT_Z * PX)
                // About +Y: positive is anticlockwise seen from above.
                .rotate(rad(-KNOB_MAX_DEG * be.getKnobTurn(partialTicks)), Direction.UP)
                .translate(-KNOB_PIVOT_X * PX, 0, -KNOB_PIVOT_Z * PX)
                .light(deckLight)
                .renderInto(ms, vb);

        part(CDCPartialModels.CONSOLE_TRAJECTORY_LEVER, state, faceTurn)
                .translate(0, LEVER_PIVOT_Y * PX, LEVER_PIVOT_Z * PX)
                .rotate(rad(LEVER_DOWN_DEG * be.getLeverThrow(partialTicks)), Direction.EAST)
                .translate(0, -LEVER_PIVOT_Y * PX, -LEVER_PIVOT_Z * PX)
                .light(deckLight)
                .renderInto(ms, vb);

        part(CDCPartialModels.CONSOLE_FIRE_BUTTON, state, faceTurn)
                .translate(0, -BUTTON_TRAVEL_PX * PX * be.getButtonPress(partialTicks), 0)
                .light(deckLight)
                .renderInto(ms, vb);

        boolean connected = be.showsMission();
        FUZE_READOUT.draw(font, ms, buffer, front,
                FireMissionConsoleBlockEntity.formatFuzeOffset(be.getFuzeOffsetTicks()), "+0.0", deckLight);
        TRAJECTORY_READOUT.draw(font, ms, buffer, front,
                !connected ? "---" : be.isPlunging() ? "PLG" : "DIR", "PLG", deckLight);
        READY_READOUT.draw(font, ms, buffer, front, !connected ? "-/-"
                : Math.min(be.getGunsReady(), MAX_GUNS_SHOWN) + "/" + Math.min(be.getGunsTotal(), MAX_GUNS_SHOWN),
                "0/0", deckLight);
    }

    /** The controls stand on the block's top, above its own space (the lever reaches 2.25 px up). */
    @Override
    public AABB getRenderBoundingBox(FireMissionConsoleBlockEntity be) {
        return new AABB(be.getBlockPos()).expandTowards(0, 3 * PX, 0);
    }

    /** A partial, turned to the block's facing; transforms after this are in model space. */
    private static SuperByteBuffer part(PartialModel partial, BlockState state, float faceTurn) {
        return CachedBuffers.partial(partial, state).rotateCentered(faceTurn, Direction.UP);
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
