package io.github.daniel366cobra.createdangerclose.client.rangefinder;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Set;

/** Zoom, mouse sensitivity and HUD handling while looking through a rangefinder. */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID, value = Dist.CLIENT)
public final class RangefinderClientEvents {

    // HUD elements that would sit on top of the eyepiece view.
    private static final Set<ResourceLocation> HIDDEN_LAYERS = Set.of(
            VanillaGuiLayers.CROSSHAIR,
            VanillaGuiLayers.HOTBAR,
            VanillaGuiLayers.SELECTED_ITEM_NAME,
            VanillaGuiLayers.EXPERIENCE_BAR,
            VanillaGuiLayers.EXPERIENCE_LEVEL,
            VanillaGuiLayers.PLAYER_HEALTH,
            VanillaGuiLayers.ARMOR_LEVEL,
            VanillaGuiLayers.FOOD_LEVEL,
            VanillaGuiLayers.AIR_LEVEL,
            VanillaGuiLayers.VEHICLE_HEALTH,
            VanillaGuiLayers.JUMP_METER);

    private RangefinderClientEvents() {}

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        RangefinderSight.tick();
    }

    /** Zoom the world view (not the hand, which is hidden anyway). */
    @SubscribeEvent
    static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (event.usedConfiguredFov() && RangefinderSight.isViewActive())
            event.setFOV(event.getFOV() * RangefinderSight.getZoom());
    }

    /**
     * Scale mouse turning by the zoom factor. Vanilla turns by
     * (s·0.6 + 0.2)³ for sensitivity s, so scaling the base term by ∛zoom
     * scales the turn rate by exactly the zoom.
     */
    @SubscribeEvent
    static void onCalculateTurn(CalculatePlayerTurnEvent event) {
        if (!RangefinderSight.isViewActive())
            return;
        double base = event.getMouseSensitivity() * 0.6 + 0.2;
        double scaled = Math.cbrt(RangefinderSight.getZoom()) * base;
        event.setMouseSensitivity((scaled - 0.2) / 0.6);
    }

    @SubscribeEvent
    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (Minecraft.getInstance().screen != null || !RangefinderSight.isViewActive())
            return;
        if (event.getScrollDeltaY() != 0) {
            RangefinderSight.scrollZoom(event.getScrollDeltaY());
            event.setCanceled(true); // don't also cycle the hotbar
        }
    }

    /**
     * RMB is the ranging button while operating (see {@link RangefinderSight#tick()}).
     * Cancel it first, so neither Create's contraption interaction nor vanilla
     * item use also handles the click.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isUseItem() && RangefinderSight.isOperating()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    @SubscribeEvent
    static void onRenderHand(RenderHandEvent event) {
        if (RangefinderSight.isViewActive())
            event.setCanceled(true);
    }

    @SubscribeEvent
    static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (RangefinderSight.isViewActive() && HIDDEN_LAYERS.contains(event.getName()))
            event.setCanceled(true);
    }

    /** Mod-bus event; the subscriber routes it to the mod bus automatically. */
    @SubscribeEvent
    static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS,
                CreateDangerClose.asResource("rangefinder_sight"), RangefinderOverlay::render);
    }
}
