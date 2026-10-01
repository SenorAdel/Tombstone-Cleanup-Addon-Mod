package dev.tombstonecleanup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;

public final class CleanupService {
    private static final Map<MinecraftServer, ReportResult> LAST_REPORTS = new WeakHashMap<>();
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private CleanupService() {}

    public static CleanupReport scan(MinecraftServer server) {
        CleanupReport report = run(server, false, "Scan", "");
        remember(server, report, persistReport(server, report));
        return report;
    }

    public static ExecutionResult execute(MinecraftServer server) {
        if (server.isCurrentlySaving()) {
            throw new IllegalStateException("The server is saving. Wait for the save to finish before executing cleanup.");
        }
        String backupId = FILE_STAMP.format(java.time.Instant.now());
        CleanupReport report = run(server, true, "Execution", backupId);
        Path path = persistReport(server, report);
        remember(server, report, path);
        return new ExecutionResult(report, path, backupId);
    }

    public static ReportResult latest(MinecraftServer server) {
        synchronized (LAST_REPORTS) {
            return LAST_REPORTS.get(server);
        }
    }

    private static void remember(MinecraftServer server, CleanupReport report, Path path) {
        synchronized (LAST_REPORTS) {
            LAST_REPORTS.put(server, new ReportResult(report, path));
        }
    }

    private static Path persistReport(MinecraftServer server, CleanupReport report) {
        try {
            return writeReport(server, report);
        } catch (IOException exception) {
            report.failure("Detailed report could not be written: " + LoadedWorldCleanup.shortMessage(exception));
            return null;
        }
    }

    private static CleanupReport run(MinecraftServer server, boolean execute, String mode, String backupId) {
        CleanupReport report = new CleanupReport(mode);
        report.notes.add("World containers, dropped items, and AE2 networks are scanned in FULL chunks already loaded by the server only.");
        report.notes.add("Tombstone-owned block entities are skipped entirely to preserve normal graves and their stored contents.");
        report.notes.add("Unloaded chunks and data in non-exposed mod storage are not scanned or changed.");

        // Each storage area is isolated so one failure cannot prevent cleanup of other areas.
        try {
            LoadedWorldCleanup.run(server, report, execute);
        } catch (RuntimeException exception) {
            report.failure("Loaded world scan stopped early: " + LoadedWorldCleanup.shortMessage(exception));
        }
        try {
            OfflinePlayerCleanup.run(server, report, execute, backupId);
        } catch (RuntimeException exception) {
            report.failure("Offline player scan stopped early: " + LoadedWorldCleanup.shortMessage(exception));
        }

        if (ModList.get().isLoaded("ae2")) {
            try {
                Class<?> integration = Class.forName("dev.tombstonecleanup.Ae2StorageCleanup", true,
                        CleanupService.class.getClassLoader());
                integration.getMethod("run", MinecraftServer.class, CleanupReport.class, boolean.class)
                        .invoke(null, server, report, execute);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                report.failure("AE2 API/storage scan failed; no further AE2 operations were attempted: "
                        + LoadedWorldCleanup.shortMessage(exception));
            }
        } else {
            report.unsupported("AE2 is not loaded. No ME storage networks were scanned.");
        }

        if (server.isCurrentlySaving() && execute) {
            report.failure("The server entered a save while cleanup was running. Stop further cleanup and review the detailed report.");
        }
        return report;
    }

    private static Path writeReport(MinecraftServer server, CleanupReport report) throws IOException {
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("tombstone-cleanup").resolve("reports");
        Files.createDirectories(directory);
        Path path = directory.resolve("report-" + FILE_STAMP.format(report.createdAt) + "-" + report.mode.toLowerCase() + ".txt");
        Files.writeString(path, report.detailedText(), java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        return path;
    }

    public record ExecutionResult(CleanupReport report, Path reportPath, String backupId) {}
    public record ReportResult(CleanupReport report, Path reportPath) {}
}
