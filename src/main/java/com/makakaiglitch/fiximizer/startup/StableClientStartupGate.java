package com.makakaiglitch.fiximizer.startup;

/**
 * One-shot gate for optional client startup work.
 *
 * This small state machine is deliberately independent of Minecraft classes
 * so its readiness and reset behavior can be tested without booting the game.
 * Call it from the client thread only.
 */
public final class StableClientStartupGate {
    private final int requiredStableTicks;

    private int stableTicks;
    private boolean completed;

    public StableClientStartupGate(int requiredStableTicks) {
        if (requiredStableTicks < 1) {
            throw new IllegalArgumentException("requiredStableTicks must be positive");
        }
        this.requiredStableTicks = requiredStableTicks;
    }

    /**
     * Records one client tick.
     *
     * @param clientReady true when a title screen or world is available
     * @return true exactly once, on the tick that reaches the stable threshold
     */
    public boolean onClientTick(boolean clientReady) {
        if (completed) {
            return false;
        }

        if (!clientReady) {
            stableTicks = 0;
            return false;
        }

        if (stableTicks < requiredStableTicks) {
            stableTicks++;
        }

        if (stableTicks == requiredStableTicks) {
            completed = true;
            return true;
        }

        return false;
    }

    public int stableTicks() {
        return stableTicks;
    }

    public boolean completed() {
        return completed;
    }
}
