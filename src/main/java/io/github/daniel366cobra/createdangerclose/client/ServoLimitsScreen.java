package io.github.daniel366cobra.createdangerclose.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoLimits;
import io.github.daniel366cobra.createdangerclose.network.ServoLimitsPayload;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.createmod.catnip.gui.ScreenOpener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * A servo's protractor dial: drag the arc's two ends. A full ring for traverse (0 at the top, clockwise),
 * a half dial for elevation (0 level). Sent to the server on close.
 */
public class ServoLimitsScreen extends AbstractSimiScreen {

    // 128 x 128, centered; the _lit sheets show only inside the allowed arc.
    private static final ResourceLocation RING = texture("ring");
    private static final ResourceLocation RING_LIT = texture("ring_lit");
    private static final ResourceLocation ELEVATION = texture("elevation");
    private static final ResourceLocation ELEVATION_LIT = texture("elevation_lit");
    // 16 x 32: normal above, held or hovered below.
    private static final ResourceLocation GRABBER = texture("grabber");
    // 16 x 64 pointing up, pivot at bottom center; the 16 x 16 hub over its root.
    private static final ResourceLocation NEEDLE = texture("needle");
    private static final ResourceLocation CENTER = texture("center");

    private static final int DIAL = 128;
    private static final int HALF = DIAL / 2;
    private static final int GRABBER_SIZE = 16;
    private static final int GRAB_RADIUS = 60;
    private static final int LABEL_RADIUS = 78;
    private static final float SECTOR_STEP_DEG = 2;
    private static final int TEXT = 0xFFEEE3CC;
    private static final int TEXT_DIM = 0xFFA89A82;
    private static final int NOW_TEXT = 0xFFE0B040;
    private static final int NEEDLE_WIDTH = 16, NEEDLE_LENGTH = 64, CENTER_SIZE = 16;

    private enum Drag { NONE, START, END, EITHER_END, BOTH }

    private final CannonServoOutputBlockEntity servo;
    private final boolean elevation;
    // Unsnapped, dial degrees.
    private float start, length;
    private Drag drag = Drag.NONE;
    private float lastMouseAngle;
    private int lastClickStep;
    private int ticksOpen;
    private final LerpedFloat needle;

    private ServoLimitsScreen(CannonServoOutputBlockEntity servo) {
        super(Component.translatable(titleKey(servo)));
        this.servo = servo;
        this.elevation = servo.isElevationDial();
        ServoLimits limits = servo.getLimits();
        this.start = limits.start();
        this.length = limits.length();
        this.needle = LerpedFloat.angular().startWithValue(servo.getDialAngle());
    }

    /** A mount's pitch or yaw, or a swivel bearing. */
    public static boolean hasDial(ServoAxis axis) {
        return axis == ServoAxis.PITCH || axis == ServoAxis.YAW || axis == ServoAxis.SWIVEL;
    }

    public static void open(CannonServoOutputBlockEntity servo) {
        if (hasDial(servo.getDetectedAxis()))
            ScreenOpener.open(new ServoLimitsScreen(servo));
    }

    private static String titleKey(CannonServoOutputBlockEntity servo) {
        String kind = servo.isElevationDial() ? "elevation" : servo.getDetectedAxis() == ServoAxis.YAW ? "traverse" : "swivel";
        return "createdangerclose.gui.servo_limits.title." + kind;
    }

    private static ResourceLocation texture(String name) {
        return CreateDangerClose.asResource("textures/gui/servo_limits/" + name + ".png");
    }

    @Override
    protected void init() {
        setWindowSize(DIAL + 2 * (LABEL_RADIUS - HALF) + 40, DIAL + 70);
        super.init();
    }

    private int centerX() {
        return width / 2;
    }

    private int centerY() {
        return height / 2;
    }

    // --- Dial geometry ---

    /** The ring from the top clockwise, elevation from the right upward. */
    private float dirX(float dial) {
        float rad = (float) Math.toRadians(dial);
        return elevation ? Mth.cos(rad) : Mth.sin(rad);
    }

    private float dirY(float dial) {
        float rad = (float) Math.toRadians(dial);
        return elevation ? -Mth.sin(rad) : -Mth.cos(rad);
    }

    private float mouseAngle(double mouseX, double mouseY) {
        double dx = mouseX - centerX(), dy = mouseY - centerY();
        return (float) Math.toDegrees(elevation ? Math.atan2(-dy, dx) : Math.atan2(dx, -dy));
    }

    private float step() {
        return hasShiftDown() ? 5 : 1;
    }

    private float snap(float value) {
        float step = step();
        return Math.round(value / step) * step;
    }

    private ServoLimits snapped() {
        float snappedLength = Mth.clamp(snap(length), 1, elevation ? 180 : 360);
        return new ServoLimits(snap(start), snappedLength);
    }

    private boolean full() {
        ServoLimits limits = snapped();
        return elevation ? limits.start() <= -90 && limits.end() >= 90 : !limits.limitsTurn();
    }

    // --- Rendering ---

    @Override
    protected void renderWindowBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int alpha = (int) (0x50 * Math.min(1, (ticksOpen + AnimationTickHolder.getPartialTicks()) / 20f));
        graphics.fillGradient(0, 0, width, height, 0x101010 | alpha << 24, 0x101010 | alpha << 24);
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int cx = centerX(), cy = centerY();
        ServoLimits limits = snapped();

        graphics.blit(elevation ? ELEVATION : RING, cx - HALF, cy - HALF, 0, 0, DIAL, DIAL, DIAL, DIAL);
        renderLitSector(graphics, limits, cx, cy);
        renderNeedle(graphics, cx, cy, needle.getValue(partialTicks));

        boolean overStart = grabberHit(mouseX, mouseY, limits.start());
        boolean overEnd = grabberHit(mouseX, mouseY, limits.end());
        renderGrabber(graphics, limits.start(), drag == Drag.START || drag == Drag.BOTH || overStart);
        renderGrabber(graphics, limits.end(), drag == Drag.END || drag == Drag.BOTH || overEnd);

        if (!full()) {
            renderLabel(graphics, limits.start());
            renderLabel(graphics, limits.end());
        }

        graphics.drawCenteredString(font, title, cx, cy - LABEL_RADIUS - 26, TEXT);
        Component state = full()
                ? Component.translatable("createdangerclose.gui.servo_limits.no_limit")
                : Component.translatable("createdangerclose.gui.servo_limits.arc", Math.round(limits.length()));
        graphics.drawCenteredString(font, state, cx, cy - LABEL_RADIUS - 15, TEXT_DIM);
        graphics.drawCenteredString(font, Component.translatable("createdangerclose.gui.servo_limits.now",
                Math.round(needle.getValue(partialTicks))), cx, cy + LABEL_RADIUS + 12, NOW_TEXT);
        graphics.drawCenteredString(font, Component.translatable("createdangerclose.gui.servo_limits.hint"),
                cx, cy + LABEL_RADIUS + 24, TEXT_DIM);
    }

    /** A fan from the center over the allowed arc. */
    private void renderLitSector(GuiGraphics graphics, ServoLimits limits, int cx, int cy) {
        RenderSystem.setShaderTexture(0, elevation ? ELEVATION_LIT : RING_LIT);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX);
        int segments = Math.max(1, Mth.ceil(limits.length() / SECTOR_STEP_DEG));
        for (int i = 0; i < segments; i++) {
            float a1 = limits.start() + limits.length() * i / segments;
            float a2 = limits.start() + limits.length() * (i + 1) / segments;
            buffer.addVertex(pose, cx, cy, 0).setUv(0.5f, 0.5f);
            fanVertex(buffer, pose, cx, cy, a1);
            fanVertex(buffer, pose, cx, cy, a2);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private void fanVertex(BufferBuilder buffer, Matrix4f pose, int cx, int cy, float dial) {
        float dx = dirX(dial) * HALF, dy = dirY(dial) * HALF;
        buffer.addVertex(pose, cx + dx, cy + dy, 0).setUv((HALF + dx) / DIAL, (HALF + dy) / DIAL);
    }

    private void renderNeedle(GuiGraphics graphics, int cx, int cy, float dial) {
        RenderSystem.enableBlend();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        // Drawn up, turned clockwise: the ring's 0 is up, elevation's to the right.
        pose.mulPose(Axis.ZP.rotationDegrees(elevation ? 90 - dial : dial));
        graphics.blit(NEEDLE, -NEEDLE_WIDTH / 2, -NEEDLE_LENGTH, 0, 0, NEEDLE_WIDTH, NEEDLE_LENGTH, NEEDLE_WIDTH, NEEDLE_LENGTH);
        pose.popPose();
        graphics.blit(CENTER, cx - CENTER_SIZE / 2, cy - CENTER_SIZE / 2, 0, 0, CENTER_SIZE, CENTER_SIZE, CENTER_SIZE, CENTER_SIZE);
        RenderSystem.disableBlend();
    }

    private void renderGrabber(GuiGraphics graphics, float dial, boolean lit) {
        int x = Math.round(centerX() + dirX(dial) * GRAB_RADIUS) - GRABBER_SIZE / 2;
        int y = Math.round(centerY() + dirY(dial) * GRAB_RADIUS) - GRABBER_SIZE / 2;
        graphics.blit(GRABBER, x, y, 0, lit ? GRABBER_SIZE : 0, GRABBER_SIZE, GRABBER_SIZE, GRABBER_SIZE, 2 * GRABBER_SIZE);
    }

    private void renderLabel(GuiGraphics graphics, float dial) {
        int x = Math.round(centerX() + dirX(dial) * LABEL_RADIUS);
        int y = Math.round(centerY() + dirY(dial) * LABEL_RADIUS) - font.lineHeight / 2;
        int value = Math.round(elevation ? dial : Mth.wrapDegrees(dial));
        graphics.drawCenteredString(font, (value > 0 ? "+" : "") + value + "°", x, y, TEXT);
    }

    private boolean grabberHit(double mouseX, double mouseY, float dial) {
        double x = centerX() + dirX(dial) * GRAB_RADIUS, y = centerY() + dirY(dial) * GRAB_RADIUS;
        return Math.abs(mouseX - x) <= GRABBER_SIZE / 2f + 1 && Math.abs(mouseY - y) <= GRABBER_SIZE / 2f + 1;
    }

    // --- Input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1) {
            // Right-click: no limits.
            ServoLimits full = elevation ? ServoLimits.FULL_ELEVATION : ServoLimits.FULL_TURN;
            start = full.start();
            length = full.length();
            click(0.5f);
            return true;
        }
        ServoLimits limits = snapped();
        boolean onStart = grabberHit(mouseX, mouseY, limits.start());
        boolean onEnd = grabberHit(mouseX, mouseY, limits.end());
        if (onStart || onEnd) {
            drag = hasControlDown() ? Drag.BOTH : onStart && onEnd ? Drag.EITHER_END : onStart ? Drag.START : Drag.END;
            lastMouseAngle = mouseAngle(mouseX, mouseY);
            lastClickStep = Math.round(lastMouseAngle / 5);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (drag == Drag.NONE)
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        float angle = mouseAngle(mouseX, mouseY);
        float delta = Mth.wrapDegrees(angle - lastMouseAngle);
        lastMouseAngle = angle;
        if (delta == 0)
            return true;
        if (drag == Drag.EITHER_END)
            // A full ring: whichever end the drag can move.
            drag = delta > 0 ? Drag.START : Drag.END;
        if (elevation)
            dragElevation(delta);
        else
            dragTurn(delta);

        int clickStep = Math.round(angle / 5);
        if (clickStep != lastClickStep) {
            lastClickStep = clickStep;
            click(0.25f + 0.5f * Mth.clamp(length / (elevation ? 180 : 360), 0, 1));
        }
        return true;
    }

    private void dragTurn(float delta) {
        switch (drag) {
            case START -> {
                float newLength = Mth.clamp(length - delta, 1, 360);
                start += length - newLength;
                length = newLength;
            }
            case END -> length = Mth.clamp(length + delta, 1, 360);
            case BOTH -> start = Mth.wrapDegrees(start + delta);
            default -> { }
        }
    }

    private void dragElevation(float delta) {
        float end = start + length;
        switch (drag) {
            case START -> start = Mth.clamp(start + delta, -90, end - 1);
            case END -> end = Mth.clamp(end + delta, start + 1, 90);
            case BOTH -> {
                float shift = Mth.clamp(delta, -90 - start, 90 - end);
                start += shift;
                end += shift;
            }
            default -> { }
        }
        length = end - start;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        drag = Drag.NONE;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private static void click(float pitch) {
        if (Minecraft.getInstance().player != null)
            Minecraft.getInstance().player.playSound(SoundEvents.LEVER_CLICK, 0.2f, pitch);
    }

    @Override
    public void tick() {
        super.tick();
        ticksOpen++;
        needle.chase(servo.getDialAngle(), 0.4, LerpedFloat.Chaser.EXP);
        needle.tickChaser();
        if (servo.isRemoved())
            onClose();
    }

    @Override
    public void onClose() {
        ServoLimits limits = snapped();
        PacketDistributor.sendToServer(new ServoLimitsPayload(servo.getBlockPos(), limits.start(), limits.length()));
        super.onClose();
    }
}