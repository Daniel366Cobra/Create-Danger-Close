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
import org.jetbrains.annotations.Nullable;

/** Model pixels, face toward north; the deck is y = 16. */
public class FireMissionConsoleRenderer extends SafeBlockEntityRenderer<FireMissionConsoleBlockEntity> {

    private static final float PX = 1 / 16f;

    // Fuze knob: vertical axis through (12.5, 16, z), z per console; clockwise from above is later.
    private static final float KNOB_PIVOT_X = 12.5f;
    private static final float KNOB_MAX_DEG = 135;
    // Shell knob, behind the fuze knob: vertical axis through (12.5, 16, 10.5), a detent per notch.
    private static final float SHELL_KNOB_PIVOT_Z = 10.5f;
    private static final float SHELL_NOTCH_DEG = 360f / FireMissionConsoleBlockEntity.SHELL_KNOB_NOTCHES;
    // Trajectory lever: east-west axis through (8.5, 15.75, 7.5), a quarter turn toward the player for plunging.
    private static final float LEVER_PIVOT_Y = 15.75f, LEVER_PIVOT_Z = 7.5f;
    private static final float LEVER_DOWN_DEG = -90;
    private static final float BUTTON_TRAVEL_PX = 0.75f;
    // Autocannon trigger: east-west axis through (3.5, 16, 7.5), toward the player.
    private static final float TRIGGER_PIVOT_Y = 16, TRIGGER_PIVOT_Z = 7.5f;
    private static final float TRIGGER_PULL_DEG = -22.5f;

    // Readout windows 0.5 px deep; text 0.25 px above their floor.
    private static final float READOUT_FRONT = 2, READOUT_BACK = 4, READOUT_HEIGHT = 15.75f, READOUT_FILL = 0.9f;
    private static final PanelText FUZE_READOUT = PanelText.top(11, 14, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    // The same window in two lines: the shell order above, the fuze correction below.
    private static final float READOUT_MIDDLE = (READOUT_FRONT + READOUT_BACK) / 2;
    private static final PanelText SHELL_LINE = PanelText.top(11, 14, READOUT_MIDDLE, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    private static final PanelText FUZE_LINE = PanelText.top(11, 14, READOUT_FRONT, READOUT_MIDDLE, READOUT_HEIGHT, READOUT_FILL);
    private static final PanelText TRAJECTORY_READOUT = PanelText.top(7, 10, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    // Four characters, centered.
    private static final PanelText AUTO_MODE_READOUT = PanelText.top(6, 10, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    private static final PanelText READY_READOUT = PanelText.top(2, 5, READOUT_FRONT, READOUT_BACK, READOUT_HEIGHT, READOUT_FILL);
    // Keeps "ready/total" to three characters.
    private static final int MAX_GUNS_SHOWN = 9;

    /** The controls, and the middle readout. A shell knob comes with the two-line left gauge. */
    private record Layout(PartialModel knob, float knobPivotZ, @Nullable PartialModel shellKnob, PartialModel lever,
                          PartialModel fireControl, boolean trigger, PanelText modeReadout) {}

    private static final Layout FIRE_MISSION = new Layout(CDCPartialModels.CONSOLE_FUZE_KNOB, 6.5f,
            CDCPartialModels.CONSOLE_SHELL_KNOB, CDCPartialModels.CONSOLE_TRAJECTORY_LEVER,
            CDCPartialModels.CONSOLE_FIRE_BUTTON, false, TRAJECTORY_READOUT);
    private static final Layout AUTOCANNON = new Layout(CDCPartialModels.AA_CONSOLE_FUZE_KNOB, 7.5f, null,
            CDCPartialModels.AA_CONSOLE_AUTO_SWITCH, CDCPartialModels.AA_CONSOLE_FIRE_TRIGGER, true,
            AUTO_MODE_READOUT);

    private final Font font;
    private final Layout layout;

    public FireMissionConsoleRenderer(BlockEntityRendererProvider.Context context) {
        this(context, FIRE_MISSION);
    }

    private FireMissionConsoleRenderer(BlockEntityRendererProvider.Context context, Layout layout) {
        this.font = context.getFont();
        this.layout = layout;
    }

    /** The same desk with an auto switch for the lever. */
    public static FireMissionConsoleRenderer autocannon(BlockEntityRendererProvider.Context context) {
        return new FireMissionConsoleRenderer(context, AUTOCANNON);
    }

    @Override
    protected void renderSafe(FireMissionConsoleBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction front = state.getValue(FireMissionConsoleBlock.FACING);
        // Light the deck by the space above.
        int deckLight = RenderLight.at(be.getLevel(), be.getBlockPos().above());
        float faceTurn = (float) Math.toRadians(-(front.toYRot() + 180));
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        knob(layout.knob(), state, faceTurn, layout.knobPivotZ(), -KNOB_MAX_DEG * be.getKnobTurn(partialTicks))
                .light(deckLight)
                .renderInto(ms, vb);
        if (layout.shellKnob() != null)
            knob(layout.shellKnob(), state, faceTurn, SHELL_KNOB_PIVOT_Z,
                    -SHELL_NOTCH_DEG * be.getShellKnobTurn(partialTicks))
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
        if (layout.shellKnob() != null) {
            // Both lines sized alike: capitals and digits share an advance in Minecraft's font.
            SHELL_LINE.draw(font, ms, buffer, front, be.shellText(), be.widestFuzeText(), deckLight);
            FUZE_LINE.draw(font, ms, buffer, front, be.fuzeText(), be.widestFuzeText(), deckLight);
        } else
            FUZE_READOUT.draw(font, ms, buffer, front, be.fuzeText(), be.widestFuzeText(), deckLight);
        // The wider setting sets the size, whatever the language.
        String up = be.modeText(false), down = be.modeText(true);
        String widestMode = font.width(down) > font.width(up) ? down : up;
        layout.modeReadout().draw(font, ms, buffer, front,
                !connected ? "-".repeat(widestMode.length()) : be.modeText(), widestMode, deckLight);
        READY_READOUT.draw(font, ms, buffer, front, !connected ? "-/-"
                : Math.min(be.getGunsReady(), MAX_GUNS_SHOWN) + "/" + Math.min(be.getGunsTotal(), MAX_GUNS_SHOWN),
                "0/0", deckLight);
    }

    /** The trigger reaches 4.5 px above the block. */
    @Override
    public AABB getRenderBoundingBox(FireMissionConsoleBlockEntity be) {
        return new AABB(be.getBlockPos()).expandTowards(0, 5 * PX, 0);
    }

    /** Turned about its vertical axis through (12.5, 16, pivotZ). */
    private static SuperByteBuffer knob(PartialModel partial, BlockState state, float faceTurn, float pivotZ, float degrees) {
        return part(partial, state, faceTurn)
                .translate(KNOB_PIVOT_X * PX, 0, pivotZ * PX)
                .rotate(rad(degrees), Direction.UP)
                .translate(-KNOB_PIVOT_X * PX, 0, -pivotZ * PX);
    }

    /** Transforms after this are in model space. */
    private static SuperByteBuffer part(PartialModel partial, BlockState state, float faceTurn) {
        return CachedBuffers.partial(partial, state).rotateCentered(faceTurn, Direction.UP);
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}