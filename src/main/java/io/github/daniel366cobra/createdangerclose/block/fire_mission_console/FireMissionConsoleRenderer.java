package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.PanelText;
import io.github.daniel366cobra.createdangerclose.client.RenderLight;
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
    // Autocannon trigger: tips about the east-west axis through (3.5, 16, 7.5), this far towards the player.
    private static final float TRIGGER_PIVOT_Y = 16, TRIGGER_PIVOT_Z = 7.5f;
    private static final float TRIGGER_PULL_DEG = -22.5f;

    // Readout windows in front of each control, 0.5 px deep: the text lies 0.25 px above their floor.
    private static final float READOUT_FRONT = 2, READOUT_BACK = 4, READOUT_HEIGHT = 15.75f, READOUT_FILL = 0.9f;
    private static final PanelText FUZE_READOUT = PanelText.top(11, 14, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    private static final PanelText TRAJECTORY_READOUT = PanelText.top(7, 10, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    // The autocannon console's mode readout: four characters, centred.
    private static final PanelText AUTO_MODE_READOUT = PanelText.top(6, 10, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    private static final PanelText READY_READOUT = PanelText.top(2, 5, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    // Single digits keep "ready/total" to three characters.
    private static final int MAX_GUNS_SHOWN = 9;

    /**
     * What a console carries: its three controls (the fire control a push button or a
     * trigger lever), and the readout in front of the middle one (with the widest text
     * it shows, which sets the text's size).
     */
    private record Layout(PartialModel knob, PartialModel lever, PartialModel fireControl, boolean trigger,
                          PanelText modeReadout, String widestMode) {}

    private static final Layout FIRE_MISSION = new Layout(CDCPartialModels.CONSOLE_FUZE_KNOB,
            CDCPartialModels.CONSOLE_TRAJECTORY_LEVER, CDCPartialModels.CONSOLE_FIRE_BUTTON, false,
            TRAJECTORY_READOUT, "PLG");
    private static final Layout AUTOCANNON = new Layout(CDCPartialModels.AA_CONSOLE_FUZE_KNOB,
            CDCPartialModels.AA_CONSOLE_AUTO_SWITCH, CDCPartialModels.AA_CONSOLE_FIRE_TRIGGER, true,
            AUTO_MODE_READOUT, "AUTO");

    private final Font font;
    private final Layout layout;

    public FireMissionConsoleRenderer(BlockEntityRendererProvider.Context context) {
        this(context, FIRE_MISSION);
    }

    private FireMissionConsoleRenderer(BlockEntityRendererProvider.Context context, Layout layout) {
        this.font = context.getFont();
        this.layout = layout;
    }

    /** The Autocannon Control Console: the same desk, with an auto switch in place of the lever. */
    public static FireMissionConsoleRenderer autocannon(BlockEntityRendererProvider.Context context) {
        return new FireMissionConsoleRenderer(context, AUTOCANNON);
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

        part(layout.knob(), state, faceTurn)
                .translate(KNOB_PIVOT_X * PX, 0, KNOB_PIVOT_Z * PX)
                // About +Y: positive is anticlockwise seen from above.
                .rotate(rad(-KNOB_MAX_DEG * be.getKnobTurn(partialTicks)), Direction.UP)
                .translate(-KNOB_PIVOT_X * PX, 0, -KNOB_PIVOT_Z * PX)
                .light(deckLight)
                .renderInto(ms, vb);

        part(layout.lever(), state, faceTurn)
                .translate(0, LEVER_PIVOT_Y * PX, LEVER_PIVOT_Z * PX)
                .rotate(rad(LEVER_DOWN_DEG * be.getLeverThrow(partialTicks)), Direction.EAST)
                .translate(0, -LEVER_PIVOT_Y * PX, -LEVER_PIVOT_Z * PX)
                .light(deckLight)
                .renderInto(ms, vb);

        SuperByteBuffer fireControl = part(layout.fireControl(), state, faceTurn);
        float press = be.getButtonPress(partialTicks);
        if (layout.trigger())
            fireControl.translate(0, TRIGGER_PIVOT_Y * PX, TRIGGER_PIVOT_Z * PX)
                    .rotate(rad(TRIGGER_PULL_DEG * press), Direction.EAST)
                    .translate(0, -TRIGGER_PIVOT_Y * PX, -TRIGGER_PIVOT_Z * PX);
        else
            fireControl.translate(0, -BUTTON_TRAVEL_PX * PX * press, 0);
        fireControl.light(deckLight).renderInto(ms, vb);

        boolean connected = be.showsMission();
        FUZE_READOUT.draw(font, ms, buffer, front,
                be.fuzeText(), be.widestFuzeText(), deckLight);
        layout.modeReadout().draw(font, ms, buffer, front,
                !connected ? "-".repeat(layout.widestMode().length()) : be.modeText(), layout.widestMode(), deckLight);
        READY_READOUT.draw(font, ms, buffer, front, !connected ? "-/-"
                : Math.min(be.getGunsReady(), MAX_GUNS_SHOWN) + "/" + Math.min(be.getGunsTotal(), MAX_GUNS_SHOWN),
                "0/0", deckLight);
    }

    /** The controls stand on the block's top, above its own space (the trigger reaches 4.5 px up). */
    @Override
    public AABB getRenderBoundingBox(FireMissionConsoleBlockEntity be) {
        return new AABB(be.getBlockPos()).expandTowards(0, 5 * PX, 0);
    }

    /** A partial, turned to the block's facing; transforms after this are in model space. */
    private static SuperByteBuffer part(PartialModel partial, BlockState state, float faceTurn) {
        return CachedBuffers.partial(partial, state).rotateCentered(faceTurn, Direction.UP);
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
