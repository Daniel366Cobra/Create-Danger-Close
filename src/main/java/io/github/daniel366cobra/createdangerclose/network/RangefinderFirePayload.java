package io.github.daniel366cobra.createdangerclose.network;

import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoStatus;
import io.github.daniel366cobra.createdangerclose.api.sensor.ComputerServo;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonFiring;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderOptics;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashSet;
import java.util.Set;

/**
 * TEMPORARY TEST AID. Client to server: the rangefinder operator clicked the
 * attack button, so fire every cannon mount whose servos are on the
 * rangefinder's battle network. It lets one player track a target and shoot
 * at it without a second player at the guns.
 *
 * <p>To be replaced by the Firing Circuit module; switch it off with
 * {@link #ENABLED}.
 */
public record RangefinderFirePayload() implements CustomPacketPayload {

    /** The one switch for this test aid: the client does not send, and the server ignores, when false. */
    public static final boolean ENABLED = true;

    public static final Type<RangefinderFirePayload> TYPE =
            new Type<>(CreateDangerClose.asResource("rangefinder_fire"));

    public static final StreamCodec<ByteBuf, RangefinderFirePayload> STREAM_CODEC =
            StreamCodec.unit(new RangefinderFirePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RangefinderFirePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!ENABLED || !(context.player().level() instanceof ServerLevel level))
                return;
            RangefinderBearingBlockEntity bearing = RangefinderOptics.findOperatedBearing(context.player());
            if (bearing == null)
                return;
            KineticNetwork network = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(bearing);
            if (network == null)
                return;

            // A mount usually has a pitch and a yaw servo: fire it once.
            Set<BlockPos> mounts = new HashSet<>();
            for (KineticBlockEntity member : network.members.keySet()) {
                if (!(member instanceof ComputerServo servo))
                    continue;
                ServoStatus status = servo.getServoStatus();
                if (status == null)
                    continue;
                for (ServoStatus.MountSnapshot mount : status.mounts())
                    mounts.add(mount.mountPos());
            }
            for (BlockPos mountPos : mounts)
                CannonFiring.fire(level, mountPos);
        });
    }
}
