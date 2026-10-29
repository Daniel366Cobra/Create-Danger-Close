package io.github.daniel366cobra.createdangerclose.client.rangefinder;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderOptics;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderSteadiness;
import io.github.daniel366cobra.createdangerclose.network.RangefinderRangingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Client state of the rangefinder view: operated tube, zoom, predicted marker separation, ranging button. */
public final class RangefinderSight {

    // FOV multipliers: 2x, 5x, 10x.
    private static final float[] ZOOM_LEVELS = {0.5f, 0.2f, 0.1f};

    // Minecraft pitch is negative looking up. Short of the zenith, where the bearing and stabilizer degenerate.
    public static final float MAX_ELEVATION_DEG = 60f;
    public static final float MAX_DEPRESSION_DEG = 20f;
    // The look may lead the tube and lenses by this much, so a slow tube can't be overtaken.
    public static final float OFF_BORE_DEG = 10f;

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

    /**
     * Degrees clockwise from north. A fort knows where north is; a ship, which turns freely, only with
     * a heading sensor. Null without, or when not operating.
     */
    @Nullable
    public static Double getBearing() {
        RangefinderOptics.Sight sight = RangefinderOptics.lineOfSight(Minecraft.getInstance().player);
        if (sight == null)
            return null;
        RangefinderBearingBlockEntity bearing = getOperatedBearing();
        if (RangefinderOptics.operatedShip(Minecraft.getInstance().player) != null
                && (bearing == null || !bearing.isStabilizedYaw()))
            return null;
        return RangefinderOptics.compassBearing(sight.direction());
    }

    /**
     * Degrees clockwise from the bow a Navigation Console names, in the ship's frame (so its pitch and
     * roll do not skew it). Null without one, ashore, or when not operating.
     */
    @Nullable
    public static Double getRelativeBearing() {
        RangefinderOptics.Sight sight = RangefinderOptics.lineOfSight(Minecraft.getInstance().player);
        SubLevel ship = RangefinderOptics.operatedShip(Minecraft.getInstance().player);
        RangefinderBearingBlockEntity bearing = getOperatedBearing();
        Direction bow = bearing != null ? bearing.getBow() : null;
        if (sight == null || ship == null || bow == null)
            return null;
        Vec3 look = sight.direction();
        Vector3d local = new Quaterniond(ship.logicalPose().orientation())
                .transformInverse(new Vector3d(look.x, look.y, look.z));
        double relative = RangefinderOptics.compassBearing(new Vec3(local.x, 0, local.z))
                - RangefinderOptics.compassBearing(new Vec3(bow.getStepX(), 0, bow.getStepZ()));
        return (relative % 360 + 360) % 360;
    }

    /** Degrees above the horizon; a ship, which rolls, needs a Gimbal Sensor for it. Null without. */
    @Nullable
    public static Double getElevation() {
        RangefinderOptics.Sight sight = RangefinderOptics.lineOfSight(Minecraft.getInstance().player);
        if (sight == null)
            return null;
        RangefinderBearingBlockEntity bearing = getOperatedBearing();
        if (RangefinderOptics.operatedShip(Minecraft.getInstance().player) != null
                && (bearing == null || !bearing.isStabilizedTilt()))
            return null;
        return Math.toDegrees(Math.asin(Mth.clamp(sight.direction().y, -1, 1)));
    }

    /** The operator's yaw kept within {@link #OFF_BORE_DEG} of the tube's. */
    public static float clampYaw(float yaw, float previous, float partialTicks) {
        RangefinderBearingBlockEntity bearing = getOperatedBearing();
        if (bearing == null)
            return yaw;
        float bore = bearing.getTubeYaw(partialTicks);
        float offset = Mth.wrapDegrees(yaw - bore);
        return yaw + offBore(offset, Mth.wrapDegrees(previous - bore)) - offset;
    }

    /** The operator's pitch kept within {@link #OFF_BORE_DEG} of the lenses' and the optics' travel. */
    public static float clampPitch(float pitch, float previous, float partialTicks) {
        RangefinderBearingBlockEntity bearing = getOperatedBearing();
        if (bearing != null) {
            float bore = -bearing.getLensPitch(partialTicks);
            pitch = bore + offBore(pitch - bore, previous - bore);
        }
        return Mth.clamp(pitch, -MAX_ELEVATION_DEG, MAX_DEPRESSION_DEG);
    }

    /** A look already further off (just seated, or the tube parking) may only come back, so it never snaps. */
    private static float offBore(float offset, float previousOffset) {
        return Mth.clamp(offset, Math.min(-OFF_BORE_DEG, previousOffset), Math.max(OFF_BORE_DEG, previousOffset));
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