package com.ghostchu.quickshop.addon.exchange.core.model;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

public final class TimeOrderedIdGenerator implements Supplier<UUID> {
  private final LongSupplier epochMillis;
  private final RandomGenerator random;
  private long lastMillis = -1;
  private int sequence;

  public TimeOrderedIdGenerator(LongSupplier epochMillis, RandomGenerator random) {
    this.epochMillis = epochMillis;
    this.random = random;
  }

  @Override
  public synchronized UUID get() {
    long millis = Math.max(epochMillis.getAsLong(), lastMillis);
    if (millis == lastMillis) {
      sequence = (sequence + 1) & 0x0fff;
      if (sequence == 0) millis = ++lastMillis;
    } else {
      lastMillis = millis;
      sequence = random.nextInt(0x1000);
    }
    long most = ((millis & 0x0000ffffffffffffL) << 16)
        | 0x7000L | sequence;
    long least = (random.nextLong() & 0x3fffffffffffffffL)
        | 0x8000000000000000L;
    return new UUID(most, least);
  }
}
