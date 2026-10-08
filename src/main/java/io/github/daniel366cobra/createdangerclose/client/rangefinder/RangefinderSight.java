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

/** Client state of the rangefinder view: operated tube, zoom, predicted marker separation, ranging button. */
public final class RangefinderSight {

    // FOV multipliers: 2x, 5x, 10x.
    private static final float[] ZOOM_LEVELS = {0.5f, 0.2f, 0.1f};

    // Minecraft pitch is negative looking up.
    public static final float MAX_ELEVATION_DEG = 30f;
    public static final float MAX_DEPRESSION_DEG = 20f;

    private static int zoomIndex = 0;
    // Predicted locally; the server runs the same logic for readings.
    private static final RangefinderSteadiness STEADINESS = new RangefinderSteadiness();
    // Last sent to the server.
    private static boolean rangingSent;

    private RangefinderSight() {}

    @Nullable
    public static RangefinderContraption getOperatedTube() {
        return RangefinderOptics.findOperatedTube(Minecraft.getInstance().player);
    }

    @Nullable
    public static RangefinderBearingBlockEntity getOperatedBearing() {
        return RangefinderOptics.findOperatedBearing(Minecraft.getInstance().player);
    }

    /** Level coordinates (Sable converts to world). Null for the default eye position. */
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

    /** Operating, in first person. */
    public static boolean isViewActive() {
        return isOperating() && Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    public static float getZoom() {
        return ZOOM_LEVELS[zoomIndex];
    }

    public static void scrollZoom(double scrollDelta) {
        int step = scrollDelta > 0 ? 1 : -1;
        zoomIndex = Math.clamp(zoomIndex + step, 0, ZOOM_LEVELS.length - 1);
    }

    /** 0 = merged, 1 = fully apart. */
    public static float getMarkerSeparation(float partialTick) {
        return STEADINESS.getSeparation(partialTick);
    }

    /** Predicted locally. */
    public static boolean canRange() {
        return STEADINESS.canRange();
    }

    /** Predicted locally. */
    public static boolean canTrackVelocity() {
        return STEADINESS.canTrackVelocity();
    }

    public static boolean isRanging() {
        return rangingSent;
    }

    /** Degrees, or null when not operating. */
    @Nullable
    public static Double getBearing() {
        RangefinderOptics.Sight sight = RangefinderOptics.lineOfSight(Minecraft.getInstance().player);
        return sight != null ? RangefinderOptics.compassBearing(sight.direction()) : null;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        // Before the line of sight is taken.
        SightStabilizer.tick(mc.player, getOperatedBearing());
        RangefinderOptics.Sight sight = RangefinderOptics.lineOfSight(mc.player);
        if (sight == null) {
            zoomIndex = 0;
            STEADINESS.reset();
            rangingSent = false; // the server stops ranging when the operator leaves
            return;
        }

        RangefinderBearingBlockEntity bearing = getOperatedBearing();
        STEADINESS.update(sight.direction(), bearing == null || bearing.isOnTarget());

        boolean ranging = isViewActive() && mc.screen == null && mc.options.keyUse.isDown();
        if (ranging != rangingSent) {
            PacketDistributor.sendToServer(new RangefinderRangingPayload(ranging));
            rangingSent = ranging;
        }
    }
}