package dev.tombstonecleanup;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/** Offline player files are handled with Minecraft 26.1's ItemStackWithSlot codec and registry ops. */
public final class OfflinePlayerCleanup {
    private OfflinePlayerCleanup() {}

    public static void run(MinecraftServer server, CleanupReport report, boolean execute, String backupId) {
        Path playerDirectory = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        if (!Files.isDirectory(playerDirectory)) return;
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        try (DirectoryStream<Path> files = Files.newDirectoryStream(playerDirectory, "*.dat")) {
            for (Path file : files) {
                processFile(server, file, ops, report, execute, backupId);
            }
        } catch (IOException exception) {
            report.failure("Offline player data directory could not be listed: " + LoadedWorldCleanup.shortMessage(exception));
        }
    }

    private static void processFile(MinecraftServer server, Path file, RegistryOps<Tag> ops, CleanupReport report,
            boolean execute, String backupId) {
        UUID playerId = playerId(file);
        if (playerId == null) {
            report.failure("Offline player file has an invalid UUID filename: " + file.getFileName());
            return;
        }
        if (server.getPlayerList().getPlayer(playerId) != null) {
            report.offlinePlayersSkippedOnline++;
            return;
        }

        List<PendingStack> pending = new ArrayList<>();
        boolean findingsRecorded = false;
        try {
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
            String originalHash = sha256(file);
            CompoundTag original = NbtIo.readCompressed(file, NbtAccounter.uncompressedQuota());
            // Match PlayerDataStorage.load: update legacy offline player data before decoding
            // ItemStackWithSlot entries, so pre-component enchantments are visible to the cleaner.
            CompoundTag updated = DataFixTypes.PLAYER.updateToCurrentVersion(
                    server.getFixerUpper(), original.copy(), NbtUtils.getDataVersion(original));
            NbtUtils.addCurrentDataVersion(updated);
            boolean changed = cleanList(updated, "Inventory", "Offline player inventory", ops, report, execute, pending);
            changed |= cleanList(updated, "EnderItems", "Offline Ender Chests", ops, report, execute, pending);
            report.offlinePlayersProcessed++;

            if (!execute || !changed) {
                recordFindings(pending, report);
                findingsRecorded = true;
                return;
            }
            if (server.getPlayerList().getPlayer(playerId) != null) {
                report.failure("Offline player " + playerId + " became online before its file could be replaced; no file changes were made.");
                recordFindings(pending, report);
                findingsRecorded = true;
                return;
            }
            if (!sameFileState(file, before, originalHash)) {
                report.failure("Offline player " + playerId + " data changed during cleanup; no file changes were made.");
                recordFindings(pending, report);
                findingsRecorded = true;
                return;
            }

            Path temporary = Files.createTempFile(file.getParent(), playerId + "-cleanup-", ".dat.tmp");
            try {
                NbtIo.writeCompressed(updated, temporary);
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                CompoundTag written = NbtIo.readCompressed(temporary, NbtAccounter.uncompressedQuota());
                if (!updated.equals(written)) {
                    throw new IOException("temporary player data failed read-back verification");
                }
                if (server.getPlayerList().getPlayer(playerId) != null || !sameFileState(file, before, originalHash)) {
                    report.failure("Offline player " + playerId + " data changed immediately before replacement; the original file remains in place.");
                    recordFindings(pending, report);
                    findingsRecorded = true;
                    return;
                }
                Path backup = createBackup(server, file, playerId, backupId, originalHash);
                if (server.getPlayerList().getPlayer(playerId) != null || !sameFileState(file, before, originalHash)) {
                    report.failure("Offline player " + playerId + " data changed while its backup was being verified; the original file remains in place.");
                    recordFindings(pending, report);
                    findingsRecorded = true;
                    return;
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    report.failure("Offline player " + playerId + " file system does not support atomic replacement; original file preserved.");
                    recordFindings(pending, report);
                    findingsRecorded = true;
                    return;
                }
                recordFindings(pending, report);
                findingsRecorded = true;
                for (PendingStack stack : pending) {
                    if (stack.analysis.delete || stack.analysis.changed) {
                        report.recordApplied(stack.category, stack.analysis);
                    }
                }
                report.notes.add("Offline player " + playerId + " was backed up to " + server.getWorldPath(LevelResource.ROOT).relativize(backup));
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception exception) {
            report.failure("Offline player " + playerId + " could not be processed: " + LoadedWorldCleanup.shortMessage(exception));
            if (!findingsRecorded) recordFindings(pending, report);
        }
    }

    private static boolean cleanList(CompoundTag root, String key, String category, RegistryOps<Tag> ops,
            CleanupReport report, boolean execute, List<PendingStack> pending) throws IOException {
        Tag raw = root.get(key);
        if (!(raw instanceof ListTag list)) return false;
        ListTag replacement = new ListTag();
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Tag entry = list.get(i);
            DataResult<ItemStackWithSlot> decoded = ItemStackWithSlot.CODEC.parse(ops, entry);
            Optional<ItemStackWithSlot> slotValue = decoded.result();
            if (slotValue.isEmpty()) {
                String error = decoded.error().map(value -> value.message()).orElse("unknown item stack codec error");
                throw new IOException("could not decode " + key + " slot entry " + i + ": " + error);
            }
            ItemStackWithSlot slot = slotValue.get();
            ItemStack source = slot.stack();
            StackCleaner.Analysis analysis = StackCleaner.inspect(source, execute);
            pending.add(new PendingStack(category, analysis));
            if (execute && analysis.delete) {
                changed = true;
            } else if (execute && analysis.changed) {
                DataResult<Tag> encoded = ItemStackWithSlot.CODEC.encodeStart(ops, new ItemStackWithSlot(slot.slot(), analysis.stack));
                Optional<Tag> encodedTag = encoded.result();
                if (encodedTag.isEmpty()) {
                    String error = encoded.error().map(value -> value.message()).orElse("unknown item stack codec error");
                    throw new IOException("could not encode " + key + " slot " + slot.slot() + ": " + error);
                }
                replacement.add(encodedTag.get());
                changed = true;
            } else {
                replacement.add(entry.copy());
            }
        }
        if (execute && changed) root.put(key, replacement);
        return execute && changed;
    }

    private static void recordFindings(List<PendingStack> pending, CleanupReport report) {
        for (PendingStack stack : pending) report.recordAnalysis(stack.category, stack.analysis);
    }

    private static Path createBackup(MinecraftServer server, Path file, UUID playerId, String backupId, String originalHash)
            throws IOException, NoSuchAlgorithmException {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path backupDirectory = worldRoot.resolve("tombstone-cleanup-backups").resolve(backupId).resolve("players/data");
        Files.createDirectories(backupDirectory);
        Path backup = backupDirectory.resolve(playerId + ".dat");
        Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        if (!originalHash.equals(sha256(backup))) {
            Files.deleteIfExists(backup);
            throw new IOException("offline-player backup verification failed");
        }
        return backup;
    }

    private static boolean sameFileState(Path file, BasicFileAttributes before, String originalHash) throws IOException, NoSuchAlgorithmException {
        if (!Files.isRegularFile(file)) return false;
        BasicFileAttributes current = Files.readAttributes(file, BasicFileAttributes.class);
        return current.size() == before.size()
                && current.lastModifiedTime().equals(before.lastModifiedTime())
                && java.util.Objects.equals(current.fileKey(), before.fileKey())
                && originalHash.equals(sha256(file));
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static UUID playerId(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".dat")) return null;
        try {
            return UUID.fromString(name.substring(0, name.length() - 4));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record PendingStack(String category, StackCleaner.Analysis analysis) {}
}
