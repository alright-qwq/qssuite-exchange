package com.ghostchu.quickshop.addon.exchange.operations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditExporterTest {
  @Test
  void writesOnlyRequestedRecordsAsUtf8CsvInsideAuditDirectory() throws Exception {
    Path directory = Files.createTempDirectory("exchange-audit-");
    Instant start = Instant.parse("2026-07-28T00:00:00Z");
    AuditRecord included = new AuditRecord(UUID.randomUUID(), UUID.randomUUID(), "FORCE_CANCEL",
        "order-1", "suspected abuse", "OPEN", "CANCELLED", start.plusSeconds(1));
    AuditRecord excluded = new AuditRecord(UUID.randomUUID(), UUID.randomUUID(), "FORCE_CANCEL",
        "order-2", "suspected abuse", "OPEN", "CANCELLED", start.minusSeconds(1));

    Path exported = new AuditExporter().export(directory, List.of(included, excluded), start,
        start.plusSeconds(10));

    assertThat(exported).startsWith(directory);
    assertThat(exported.getFileName().toString()).matches("audit-[0-9TZ-]+-[0-9a-f-]+\\.csv");
    assertThat(Files.readString(exported)).contains("FORCE_CANCEL", "order-1")
        .doesNotContain("order-2");
  }

  @Test
  void prunesOnlyOwnExpiredExportsKeepingRecentAndForeignFiles() throws Exception {
    Path directory = Files.createTempDirectory("exchange-audit-prune-");
    Path old = directory.resolve("audit-20260101T000000Z-" + UUID.randomUUID() + ".csv");
    Path recent = directory.resolve("audit-20260728T000000Z-" + UUID.randomUUID() + ".csv");
    Path foreign = directory.resolve("notes.txt");
    Files.writeString(old, "old");
    Files.writeString(recent, "recent");
    Files.writeString(foreign, "keep");
    long now = System.currentTimeMillis();
    Files.setLastModifiedTime(old, FileTime.fromMillis(now - Duration.ofDays(2).toMillis()));
    Files.setLastModifiedTime(recent, FileTime.fromMillis(now));

    int removed = new AuditExporter().retain(directory, Duration.ofDays(1));

    assertThat(removed).isEqualTo(1);
    assertThat(Files.exists(old)).isFalse();
    assertThat(Files.exists(recent)).isTrue();
    assertThat(Files.exists(foreign)).isTrue();
  }

  @Test
  void zeroRetentionKeepsEveryExport() throws Exception {
    Path directory = Files.createTempDirectory("exchange-audit-zero-");
    Path old = directory.resolve("audit-20260101T000000Z-" + UUID.randomUUID() + ".csv");
    Files.writeString(old, "old");
    Files.setLastModifiedTime(old,
        FileTime.fromMillis(System.currentTimeMillis() - Duration.ofDays(2).toMillis()));

    int removed = new AuditExporter().retain(directory, Duration.ZERO);

    assertThat(removed).isZero();
    assertThat(Files.exists(old)).isTrue();
  }
}
