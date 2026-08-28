package com.ghostchu.quickshop.addon.exchange.operations;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.time.Duration;

/** Streams a time-bounded audit snapshot to a newly generated UTF-8 CSV file. */
public final class AuditExporter {
  private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
      .withZone(ZoneOffset.UTC);
  private static final Pattern EXPORT_FILE = Pattern.compile("audit-.*\\.csv");

  public Path export(Path auditDirectory, List<AuditRecord> records, Instant fromInclusive,
                     Instant toExclusive) throws IOException {
    Objects.requireNonNull(auditDirectory, "auditDirectory");
    Objects.requireNonNull(records, "records");
    Objects.requireNonNull(fromInclusive, "fromInclusive");
    Objects.requireNonNull(toExclusive, "toExclusive");
    if (!fromInclusive.isBefore(toExclusive)) {
      throw new IllegalArgumentException("audit export range must be non-empty");
    }
    Path directory = auditDirectory.toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Path target = directory.resolve("audit-" + FILE_TIME.format(Instant.now()) + "-"
        + UUID.randomUUID() + ".csv").normalize();
    if (!target.getParent().equals(directory)) {
      throw new IllegalArgumentException("audit export path escapes its directory");
    }
    try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
      writer.write("audit_id,actor_id,action,target_id,reason,before_state,after_state,created_at\n");
      records.stream().filter(record -> !record.createdAt().isBefore(fromInclusive)
              && record.createdAt().isBefore(toExclusive))
          .sorted(Comparator.comparing(AuditRecord::createdAt))
          .forEach(record -> writeRecord(writer, record));
    }
    return target;
  }

  /**
   * Deletes this addon's own exported audit CSVs whose last-modified time is older than the
   * retention window. Foreign files and directories in the same folder are never touched, and a
   * pruning failure is always best-effort: the just-finished export must not fail retroactively.
   */
  public int retain(Path auditDirectory, Duration retention) {
    Objects.requireNonNull(auditDirectory, "auditDirectory");
    Objects.requireNonNull(retention, "retention");
    if (retention.isNegative()) {
      throw new IllegalArgumentException("audit export retention must not be negative");
    }
    if (retention.isZero()) {
      return 0;
    }
    Path directory = auditDirectory.toAbsolutePath().normalize();
    Instant cutoff = Instant.now().minus(retention);
    int removed = 0;
    try (java.util.stream.Stream<Path> files = Files.list(directory)) {
      for (Path file : files.toList()) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || !EXPORT_FILE.matcher(file.getFileName().toString()).matches()) {
          continue;
        }
        try {
          Instant modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
              .toInstant();
          if (modified.isBefore(cutoff)) {
            Files.deleteIfExists(file);
            removed++;
          }
        } catch (IOException perFileFailure) {
          // One unprunable file must not block the rest of the retention sweep.
        }
      }
    } catch (IOException ignored) {
      // The directory may have been removed between the export and the sweep.
    }
    return removed;
  }

  private static void writeRecord(BufferedWriter writer, AuditRecord record) {
    try {
      writer.write(String.join(",", csv(record.auditId()), csv(record.actorId()), csv(record.action()),
          csv(record.targetId()), csv(record.reason()), csv(record.beforeState()),
          csv(record.afterState()), csv(record.createdAt())));
      writer.newLine();
    } catch (IOException failure) {
      throw new AuditWriteFailure(failure);
    }
  }

  private static String csv(Object value) {
    return '"' + String.valueOf(value).replace("\"", "\"\"") + '"';
  }

  private static final class AuditWriteFailure extends RuntimeException {
    private AuditWriteFailure(IOException cause) {
      super(cause);
    }
  }
}
