package io.github.daniel366cobra.createdangerclose.client;

import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoInputBlockEntity;
import io.github.daniel366cobra.createdangerclose.network.ServoNamePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** One line: the name a servo's guns show on the consoles. Sent to the server on close. */
public class ServoNameScreen extends Screen {

    private static final int BOX_WIDTH = 160;

    private final CannonServoInputBlockEntity servo;
    private EditBox box;

    private ServoNameScreen(CannonServoInputBlockEntity servo) {
        super(Component.translatable("createdangerclose.gui.servo_name.title"));
        this.servo = servo;
    }

    public static void open(CannonServoInputBlockEntity servo) {
        Minecraft.getInstance().setScreen(new ServoNameScreen(servo));
    }

    @Override
    protected void init() {
        int x = width / 2 - BOX_WIDTH / 2, y = height / 2 - 10;
        box = new EditBox(font, x, y, BOX_WIDTH, 20, title);
        box.setMaxLength(CannonServoInputBlockEntity.MAX_NAME_LENGTH);
        box.setValue(servo.getServoName());
        box.setHint(Component.translatable("createdangerclose.gui.servo_name.hint"));
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 - 50, y + 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 26, 0xFFFFFFFF);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        String name = box.getValue().strip();
        if (!name.equals(servo.getServoName()))
            PacketDistributor.sendToServer(new ServoNamePayload(servo.getBlockPos(), name));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}