package de.derfakegamer.sentinel.scheduler;

/** A cancellable repeating task, returned by the timer methods. */
public interface TaskHandle {
    void cancel();
}
