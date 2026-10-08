package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

/** Each module in a Ballistic Computer's bank switches on one capability; duplicates add nothing. */
public enum ComputerModule {
    /** Target lead, and rate feed-forward to the servos. */
    DIFFERENTIATOR("differentiator"),
    /** Holds rangefinder solutions between readings; compensates own-ship speed. */
    INTEGRATOR("integrator"),
    /** Merges several rangefinders on one target into a better fix. */
    CORRELATOR("correlator"),
    /** Sets timed fuzes to the time of flight. */
    FUZE_COMPUTER("fuze_computer");

    private final String id;

    ComputerModule(String id) {
        this.id = id;
    }

    /** Also used in translation keys. */
    public String id() {
        return id;
    }
}