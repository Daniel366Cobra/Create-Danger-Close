package io.github.daniel366cobra.createdangerclose.registries;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;

public class CDCPartialModels {
    public static final PartialModel SERVO_INPUT_COUPLER = block("cannon_servo/input_coupler");
    public static final PartialModel SERVO_OUTPUT_COUPLER = block("cannon_servo/output_coupler");
    public static final PartialModel SERVO_TRANSFER_COUPLER = block("cannon_servo/transfer_coupler");

    // Ballistic Computer front panel.
    public static final PartialModel COMPUTER_BEARING_NEEDLE = block("computer/ballistic_computer/bearing_needle");
    public static final PartialModel COMPUTER_CAM_BODY = block("computer/ballistic_computer/ballistic_cam_body");
    public static final PartialModel COMPUTER_CAM_FOLLOWER = block("computer/ballistic_computer/ballistic_cam_follower");

    // Differentiator.
    public static final PartialModel COMPUTER_DIFFERENTIAL = block("computer/differentiator/differential");
    public static final PartialModel COMPUTER_TRACKING_FLAG = block("computer/differentiator/tracking_flag");

    private static PartialModel block(String path) {
        return PartialModel.of(CreateDangerClose.asResource("block/" + path));
    }

    public static void register() {

    }
}