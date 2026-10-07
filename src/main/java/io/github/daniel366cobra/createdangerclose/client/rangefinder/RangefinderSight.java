package io.github.daniel366cobra.createdangerclose.client.rangefinder;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderOptics;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderSteadiness;
import io.github.daniel366cobra.createdangerclose.network.RangefinderRangingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side state of the rangefinder view: whether the local player is
 * operating a tube, the zoom level, the (predicted) marker separation, and
 * the ranging button.
 */
public final class RangefinderSight {

    // Field-of-view multipliers, cycled with the scroll wheel: 2x, 5x, 10x.
    private static final float[] ZOOM_LEVELS = {0.5f, 0.2f, 0.1f};

    // Pitch limits while operating. Minecraft pitch is negative when looking up.
    public static final float MAX_ELEVATION_DEG = 30f;
    public static final float MAX_DEPRESSION_DEG = 20f;

    private static int zoomIndex = 0;
    // Predicted locally for smooth markers; the server runs the same logic
    // to decide when readings are produced.
    private static final RangefinderSteadiness STEADINESS = new RangefinderSteadiness();
    // Ranging button state last sent to the server.
    private static boolean rangingSent;

    private RangefinderSight() {}

    /** The tube whose eyepiece the local player is seated at, if any. */
    @Nullable
    public static RangefinderContraption getOperatedTube() {
        return RangefinderOptics.findOperatedTube(Minecraft.getInstance().player);
    }

    /** The bearing carrying the operated tube, if any. */
    @Nullable
    public static RangefinderBearingBlockEntity getOperatedBearing() {
        return RangefinderOptics.findOperatedBearing(Minecraft.getInstance().player);
    }

    /**
     * First-person camera position for the operator, in the contraption's
     * level coordinates (Sable converts ship coordinates to world when the
     * camera is placed). Null to keep the default eye position.
     */
    @Nullable
    public static Vec3 getOperatorCameraPosition(float partialTicks) {
        AbstractContraptionEntity contraptionEntity = RangefinderOptics.findOperatedContraption(Minecraft.getInstance().player);
        if (contraptionEntity == null)
            return null;
        Vec3 local = ((RangefinderContraption) contraptionEntity.getContraption()).getOperatorViewPoint();
        return local != null ? contraptionEntity.toGlobalVector(local, partialTicks) : null;
    }

    public static boolean isOperating() {
        return getOperatedTube() != null;
    }

    /** Operating and looking through the eyepiece (first person). */
    public static boolean isViewActive() {
        return isOperating() && Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    /** Current field-of-view multiplier. */
    public static float getZoom() {
        return ZOOM_LEVELS[zoomIndex];
    }

    /** Scroll up zooms in, scroll down zooms out. */
    public static void scrollZoom(double scrollDelta) {
        int step = scrollDelta > 0 ? 1 : -1;
        zoomIndex = Math.clamp(zoomIndex + step, 0, ZOOM_LEVELS.length - 1);
    }

    /** 0 = markers merged, 1 = fully apart; interpolated for smooth rendering. */
    public static float getMarkerSeparation(float partialTick) {
        return STEADINESS.getSeparation(partialTick);
    }

    /** Converged enough for range readings (predicted locally). */
    public static boolean canRange() {
        return STEADINESS.canRange();
    }

    /** Converged enough for velocity readings (predicted locally). */
    public static boolean canTrackVelocity() {
        return STEADINESS.canTrackVelocity();
    }

    /** The ranging button is held. */
    public static boolean isRanging() {
        return rangingSent;
    }

    /** True bearing of the line of sight, degrees, or null when not operating. */
    @Nullable
    public static Double getBearing() {
        RangefinderOptics.Sight sight = RangefinderOptics.lineOfSight(Minecraft.getInstance().player);
        return sight != null ? RangefinderOptics.compassBearing(sight.direction()) : null;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        // Before the line of sight is taken: the sight holds against the ship's motion, if stabilised.
        SightStabiliser.tick(mc.player, getOperatedBearing());
        RangefinderOptics.Sight sight = RangefinderOptics.lineOfSight(mc.player);
        if (sight == null) {
            zoomIndex = 0;
            STEADINESS.reset();
            rangingSent = false; // the server stops ranging when the operator leaves
            return;
        }

        RangefinderBearingBlockEntity bearing = getOperatedBearing();
        STEADINESS.update(sight.direction(), bearing == null || bearing.isOnTarget());

        // Hold RMB to range; the server takes the readings.
        boolean ranging = isViewActive() && mc.screen == null && mc.options.keyUse.isDown();
        if (ranging != rangingSent) {
            PacketDistributor.sendToServer(new RangefinderRangingPayload(ranging));
            rangingSent = ranging;
        }
    }
}
