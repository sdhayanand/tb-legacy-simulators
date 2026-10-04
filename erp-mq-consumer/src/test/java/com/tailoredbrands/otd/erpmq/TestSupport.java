package com.tailoredbrands.otd.erpmq;

import java.time.Duration;
import java.util.function.Supplier;

/** Tiny polling helper (keeps the tests free of extra libraries). */
final class TestSupport {

    private TestSupport() {
    }

    /** Polls {@code supplier} every 250 ms until it returns non-null or {@code timeout} elapses (then returns null). */
    static <T> T await(Supplier<T> supplier, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(250L);
        }
        return supplier.get();
    }
}
