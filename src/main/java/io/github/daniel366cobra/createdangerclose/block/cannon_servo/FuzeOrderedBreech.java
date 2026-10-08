package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

/**
 * An autocannon breech that sets the fuze of each round as it takes it to fire (mixed
 * into CBC's breech): rounds pass through a hopper-fed breech too quickly to be set
 * while they wait.
 */
public interface FuzeOrderedBreech {

    /**
     * The fuze to set on the rounds it fires from now on: a timed fuze's time (0 or less:
     * as loaded), a delayed fuze's delay (below 0: as loaded). Lapses unless renewed.
     */
    void createdangerclose$orderFuzes(int timedTicks, int delayTicks, long gameTime);
}