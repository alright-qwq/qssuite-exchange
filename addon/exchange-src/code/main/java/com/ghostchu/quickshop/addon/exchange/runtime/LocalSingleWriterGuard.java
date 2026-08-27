package com.ghostchu.quickshop.addon.exchange.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local guard for a single addon instance using a local SQLite database. */
public final class LocalSingleWriterGuard implements SingleWriterGuard {
  private final AtomicBoolean held = new AtomicBoolean();
  private final Path lockFile;
  private FileChannel channel;
  private FileLock lock;

  /** Retained for isolated tests that do not own a database file. */
  public LocalSingleWriterGuard() {
    this.lockFile = null;
  }

  public LocalSingleWriterGuard(Path databaseFile) {
    Objects.requireNonNull(databaseFile, "databaseFile");
    this.lockFile = databaseFile.toAbsolutePath().normalize().resolveSibling(
        databaseFile.getFileName() + ".exchange-writer.lock");
  }

  @Override
  public synchronized void acquire() {
    if (!held.compareAndSet(false, true)) {
      throw new IllegalStateException("exchange writer lock is already held");
    }
    if (lockFile == null) {
      return;
    }
    try {
      channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      lock = channel.tryLock();
      if (lock == null) {
        releaseResources();
        held.set(false);
        throw new IllegalStateException("exchange writer lock is held by another server");
      }
    } catch (OverlappingFileLockException failure) {
      releaseResources();
      held.set(false);
      throw new IllegalStateException("exchange writer lock is already held", failure);
    } catch (IOException failure) {
      releaseResources();
      held.set(false);
      throw new IllegalStateException("unable to acquire exchange writer lock", failure);
    }
  }

  @Override
  public boolean held() {
    return held.get();
  }

  @Override
  public synchronized boolean runWhileHeld(GuardedWork work) throws Exception {
    Objects.requireNonNull(work, "work");
    if (!held.get()) {
      return false;
    }
    work.run();
    return true;
  }

  @Override
  public synchronized void close() {
    releaseResources();
    held.set(false);
  }

  private void releaseResources() {
    try {
      if (lock != null) {
        lock.release();
      }
    } catch (IOException ignored) {
      // Closing the channel below also releases the process lock.
    } finally {
      lock = null;
      if (channel != null) {
        try {
          channel.close();
        } catch (IOException ignored) {
          // Nothing more can be done during shutdown.
        }
        channel = null;
      }
    }
  }
}
