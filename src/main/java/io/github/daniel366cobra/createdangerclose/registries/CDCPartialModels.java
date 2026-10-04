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

    // Integrator.
    public static final PartialModel COMPUTER_INTEGRATOR_DISK = block("computer/integrator/integrator_disk");
    public static final PartialModel COMPUTER_INTEGRATOR_CARRIAGE = block("computer/integrator/integrator_carriage_body");
    public static final PartialModel COMPUTER_INTEGRATOR_BALL = block("computer/integrator/integrator_ball");
    public static final PartialModel COMPUTER_INTEGRATOR_OUTPUT_SHAFT = block("computer/integrator/integrator_output_shaft");
    public static final PartialModel COMPUTER_INTEGRATOR_HOLD_NEEDLE = block("computer/integrator/countdown_needle");

    // Correlator.
    public static final PartialModel COMPUTER_COINCIDENCE_NEEDLE = block("computer/correlator/coincidence_needle");

    // Fuze Computer.
    public static final PartialModel COMPUTER_FUZE_SETTING_RING = block("computer/fuze_computer/setting_ring");

    // Fire Mission Console.
    public static final PartialModel CONSOLE_FUZE_KNOB = block("fire_mission_console/fuze_delay_knob");
    public static final PartialModel CONSOLE_TRAJECTORY_LEVER = block("fire_mission_console/trajectory_switch_lever");
    public static final PartialModel CONSOLE_FIRE_BUTTON = block("fire_mission_console/fire_button");

    private static PartialModel block(String path) {
        return PartialModel.of(CreateDangerClose.asResource("block/" + path));
    }

    public static void register() {

    }
}