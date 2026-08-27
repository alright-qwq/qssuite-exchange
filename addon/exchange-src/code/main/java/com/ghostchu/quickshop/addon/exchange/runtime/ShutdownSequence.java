package com.ghostchu.quickshop.addon.exchange.runtime;

import java.util.Objects;
import java.util.List;
import java.util.function.Consumer;

/** Runs shutdown stages independently so an auxiliary cleanup failure cannot leak the runtime. */
public final class ShutdownSequence {
  private ShutdownSequence() {}

  public static void close(Cleanup entrypoints, Cleanup runtime, Consumer<Exception> reportFailure) {
    Objects.requireNonNull(entrypoints, "entrypoints");
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(reportFailure, "reportFailure");
    try {
      entrypoints.close();
    } catch (Exception failure) {
      reportFailure.accept(failure);
    } finally {
      try {
        runtime.close();
      } catch (Exception failure) {
        reportFailure.accept(failure);
      }
    }
  }

  /** Attempts every independent cleanup stage, reporting but isolating each failure. */
  public static void closeAll(List<Cleanup> cleanups, Consumer<Exception> reportFailure) {
    Objects.requireNonNull(cleanups, "cleanups");
    Objects.requireNonNull(reportFailure, "reportFailure");
    for (Cleanup cleanup : cleanups) {
      try {
        Objects.requireNonNull(cleanup, "cleanup").close();
      } catch (Exception failure) {
        reportFailure.accept(failure);
      }
    }
  }

  @FunctionalInterface
  public interface Cleanup {
    void close() throws Exception;
  }
}
