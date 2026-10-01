package dev.tombstonecleanup;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class CleanupCommands {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TOKEN_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static Confirmation confirmation;

    private CleanupCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tombstonecleanup")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN));

        root.then(Commands.literal("scan").executes(context -> scan(context.getSource())));
        root.then(Commands.literal("execute")
                .executes(context -> requestConfirmation(context.getSource()))
                .then(Commands.argument("token", StringArgumentType.word())
                        .executes(context -> confirmAndExecute(context.getSource(), StringArgumentType.getString(context, "token")))));
        root.then(Commands.literal("report").executes(context -> latestReport(context.getSource())));
        dispatcher.register(root);
    }

    private static int scan(CommandSourceStack source) {
        CleanupReport report = CleanupService.scan(source.getServer());
        CleanupService.ReportResult saved = CleanupService.latest(source.getServer());
        String path = saved == null || saved.reportPath() == null ? "" : "Detailed report: " + saved.reportPath();
        source.sendSuccess(() -> Component.literal(report.summary() + path), false);
        return 1;
    }

    private static int requestConfirmation(CommandSourceStack source) {
        String token = "%08X".formatted(RANDOM.nextInt());
        confirmation = new Confirmation(token, System.nanoTime() + TOKEN_LIFETIME_NANOS,
                initiator(source), source.getServer());
        source.sendSuccess(() -> Component.literal(
                "BACKUP REQUIRED: make and verify a complete server/world backup before cleanup. "
                        + "To confirm within 60 seconds, run /tombstonecleanup execute " + token + "."), false);
        return 1;
    }

    private static int confirmAndExecute(CommandSourceStack source, String suppliedToken) {
        Confirmation pending = confirmation;
        if (pending == null || System.nanoTime() > pending.expiresAtNanos
                || !pending.token.equals(suppliedToken)
                || pending.server != source.getServer()
                || !pending.initiator.equals(initiator(source))) {
            source.sendFailure(Component.literal("That cleanup confirmation token is invalid, expired, or belongs to another operator. Run /tombstonecleanup execute to request a new one."));
            return 0;
        }
        if (source.getServer().isCurrentlySaving()) {
            source.sendFailure(Component.literal("The server is saving. Wait for the save to finish, then request a new confirmation token."));
            confirmation = null;
            return 0;
        }
        confirmation = null;
        source.sendSuccess(() -> Component.literal("BACKUP REQUIRED: cleanup is starting. Confirm that a complete world backup exists before continuing."), false);
        try {
            CleanupService.ExecutionResult result = CleanupService.execute(source.getServer());
            String reportPath = result.reportPath() == null ? "Report file could not be written. " : "Detailed report: " + result.reportPath();
            source.sendSuccess(() -> Component.literal(result.report().summary()
                    + reportPath
                    + "\nOffline player backups (if changed): "
                    + source.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("tombstone-cleanup-backups").resolve(result.backupId())), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Cleanup could not start: " + exception.getMessage()));
            return 0;
        }
    }

    private static int latestReport(CommandSourceStack source) {
        try {
            CleanupService.ReportResult result = CleanupService.latest(source.getServer());
            if (result == null) {
                source.sendFailure(Component.literal("There is no scan or execution report yet. Run /tombstonecleanup scan first."));
                return 0;
            }
            String path = result.reportPath() == null ? "Detailed report file could not be written." : "Detailed report: " + result.reportPath();
            source.sendSuccess(() -> Component.literal(result.report().summary() + path), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("The report could not be written: " + exception.getMessage()));
            return 0;
        }
    }

    private static String initiator(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) return "player:" + player.getUUID();
        return "source:" + source.getTextName();
    }

    private record Confirmation(String token, long expiresAtNanos, String initiator, MinecraftServer server) {}
}
