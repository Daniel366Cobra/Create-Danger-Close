package io.github.daniel366cobra.createdangerclose.registries;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;

public class CDCPartialModels {
    public static final PartialModel SERVO_INPUT_COUPLER = block("cannon_servo/input_coupler");
    public static final PartialModel SERVO_OUTPUT_COUPLER = block("cannon_servo/output_coupler");
    public static final PartialModel SERVO_TRANSFER_COUPLER = block("cannon_servo/transfer_coupler");

    private static PartialModel block(String path) {
        return PartialModel.of(CreateDangerClose.asResource("block/" + path));
    }

    public static void register() {

    }
}