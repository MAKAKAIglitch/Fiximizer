package com.makakaiglitch.fiximizer.scheduler;

/**
 * Queue priority for Fiximizer-managed background work only.
 * It is not an operating-system thread priority and cannot reprioritize other mods.
 */
public enum TaskPriority {
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    BACKGROUND(3);

    private final int queueOrder;

    TaskPriority(int queueOrder) {
        this.queueOrder = queueOrder;
    }

    int queueOrder() {
        return queueOrder;
    }
}
