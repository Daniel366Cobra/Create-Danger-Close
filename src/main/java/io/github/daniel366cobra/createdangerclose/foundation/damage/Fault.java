package io.github.daniel366cobra.createdangerclose.foundation.damage;

/** What can be wrong with a piece of equipment. */
public enum Fault {
    /** Knocked out of true by a blast: works, but off by a fixed bias. Wrench to realign. */
    JOSTLED("jostled_case", false),
    /** A cog broken by a close blast or a hit: out of order. A cogwheel repairs it. */
    BROKEN_COG("broken_cogwheel", true),
    /** The mechanism broken by a close blast or a hit: out of order. A precision mechanism repairs it. */
    BROKEN_MECHANISM("broken_mechanism", true),
    /** Seized for want of lubrication: out of order. Honeycomb frees it. */
    SEIZED("seized", true);

    private final String langKey;
    private final boolean outOfOrder;

    Fault(String langKey, boolean outOfOrder) {
        this.langKey = langKey;
        this.outOfOrder = outOfOrder;
    }

    /** The goggles' explanation of the fault and its repair. */
    public String translationKey() {
        return "createdangerclose.error." + langKey;
    }

    /** Whether equipment with this fault does not work at all. */
    public boolean isOutOfOrder() {
        return outOfOrder;
    }
}
