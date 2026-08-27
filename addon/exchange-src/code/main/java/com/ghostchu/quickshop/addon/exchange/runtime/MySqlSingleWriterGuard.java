package com.ghostchu.quickshop.addon.exchange.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Holds MySQL's advisory writer lock on a dedicated connection for runtime lifetime. */
public final class MySqlSingleWriterGuard implements SingleWriterGuard {
  private final ConnectionFactory connections;
  private final String lockName;
  private final ScheduledExecutorService monitor;
  private final long checkIntervalMillis;
  private final ReadWriteLock fence = new ReentrantReadWriteLock();
  private final AtomicBoolean lossReported = new AtomicBoolean();
  private Connection connection;
  private Runnable onLockLost = () -> {};

  public MySqlSingleWriterGuard(ConnectionFactory connections, String databasePrefix) {
    this(connections, databasePrefix, Duration.ofSeconds(1));
  }

  MySqlSingleWriterGuard(ConnectionFactory connections, String databasePrefix,
                         Duration checkInterval) {
    this.connections = Objects.requireNonNull(connections, "connections");
    this.lockName = Objects.requireNonNull(databasePrefix, "databasePrefix") + "exchange_writer";
    if (checkInterval == null || checkInterval.isZero() || checkInterval.isNegative()) {
      throw new IllegalArgumentException("check interval must be positive");
    }
    this.checkIntervalMillis = checkInterval.toMillis();
    this.monitor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
        .daemon(true).name("qs-exchange-writer-lock-", 0).factory());
  }

  @Override
  public void acquire() throws Exception {
    fence.writeLock().lock();
    try {
      synchronized (this) {
        if (held()) throw new IllegalStateException("exchange writer lock is already held");
        Connection candidate = connections.open();
        try (PreparedStatement statement = candidate.prepareStatement("SELECT GET_LOCK(?, 0)")) {
          statement.setString(1, lockName);
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1) {
              candidate.close();
              throw new IllegalStateException("exchange writer lock is held by another server");
            }
          }
        }
        connection = candidate;
        lossReported.set(false);
        monitor.scheduleWithFixedDelay(this::checkConnection,
            checkIntervalMillis, checkIntervalMillis, TimeUnit.MILLISECONDS);
      }
    } finally {
      fence.writeLock().unlock();
    }
  }

  @Override
  public synchronized boolean held() {
    try {
      return connection != null && !connection.isClosed();
    } catch (Exception ignored) {
      return false;
    }
  }

  @Override
  public boolean runWhileHeld(GuardedWork work) throws Exception {
    Objects.requireNonNull(work, "work");
    fence.readLock().lock();
    try {
      if (!held()) {
        return false;
      }
      work.run();
      return true;
    } finally {
      fence.readLock().unlock();
    }
  }

  @Override
  public synchronized void onLockLost(Runnable action) {
    onLockLost = Objects.requireNonNull(action, "action");
  }

  @Override
  public void close() throws Exception {
    fence.writeLock().lock();
    try {
      synchronized (this) {
        monitor.shutdownNow();
        if (connection == null) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
          statement.setString(1, lockName);
          statement.execute();
        } finally {
          connection.close();
          connection = null;
        }
      }
    } finally {
      fence.writeLock().unlock();
    }
  }

  private void checkConnection() {
    Runnable listener = null;
    fence.writeLock().lock();
    try {
      synchronized (this) {
        if (connection == null) {
          return;
        }
        try {
          if (connection.isValid(1) && !connection.isClosed()) {
            return;
          }
        } catch (Exception ignored) {
          // A failed health check has the same safety meaning as a disconnected lock session.
        }
        try {
          connection.close();
        } catch (Exception ignored) {
          // The connection is already unusable.
        }
        connection = null;
        if (lossReported.compareAndSet(false, true)) {
          listener = onLockLost;
        }
      }
    } finally {
      fence.writeLock().unlock();
    }
    if (listener != null) {
      listener.run();
    }
  }

  @FunctionalInterface
  public interface ConnectionFactory {
    Connection open() throws Exception;
  }
}
