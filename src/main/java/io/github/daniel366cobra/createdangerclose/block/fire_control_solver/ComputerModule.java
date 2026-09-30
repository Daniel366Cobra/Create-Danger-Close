package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

/**
 * The modules of a fire control computer. Each one placed in the bank around
 * a Ballistic Computer switches on one capability; a second one of the same
 * kind adds nothing.
 */
public enum ComputerModule {
    /** Target lead from the rangefinders' tracked speed, and rate feed-forward to the servos. */
    DIFFERENTIATOR("differentiator"),
    /** Holds a rangefinder solution between readings, and compensates for own-ship speed. */
    INTEGRATOR("integrator"),
    /** Merges several rangefinders on one target into a better fix. */
    CORRELATOR("correlator"),
    /** Sets timed fuzes to the time of flight. */
    FUZE_COMPUTER("fuze_computer");

    private final String id;

    ComputerModule(String id) {
        this.id = id;
    }

    /** Registry path of the module's block, also used in translation keys. */
    public String id() {
        return id;
    }
}
