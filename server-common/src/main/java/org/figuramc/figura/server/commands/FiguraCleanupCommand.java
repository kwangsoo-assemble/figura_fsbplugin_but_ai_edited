package org.figuramc.figura.server.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.figuramc.figura.server.FiguraCacheCleanup;
import org.figuramc.figura.server.FiguraPermissionNodes;
import org.figuramc.figura.server.utils.ComponentUtils;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static org.figuramc.figura.server.commands.FiguraServerCommands.permissionCheck;

/**
 * {@code /fsb cleanup} - frees disk space taken by avatars and player records
 * nothing references any more. See {@link FiguraCacheCleanup} for what counts as unreferenced.
 *
 * <pre>
 *   /fsb cleanup                  report only, changes nothing
 *   /fsb cleanup confirm          delete orphan avatars and empty player records
 *   /fsb cleanup purge            report of the destructive variant
 *   /fsb cleanup purge confirm    also delete every offline player record
 * </pre>
 *
 * The report runs first on purpose: the destructive variant throws away avatars that offline
 * players uploaded, and there is no undo.
 */
public class FiguraCleanupCommand {

    public static LiteralArgumentBuilder<FiguraServerCommandSource> getCommand() {
        LiteralArgumentBuilder<FiguraServerCommandSource> cleanup = literal("cleanup");
        cleanup.requires(permissionCheck(FiguraPermissionNodes.FIGURA_CLEANUP));

        cleanup.executes(ctx -> execute(ctx.getSource(), FiguraCacheCleanup.Mode.EMPTY_ONLY, true));
        cleanup.then(confirm(FiguraCacheCleanup.Mode.EMPTY_ONLY));

        LiteralArgumentBuilder<FiguraServerCommandSource> purge = literal("purge");
        purge.executes(ctx -> execute(ctx.getSource(), FiguraCacheCleanup.Mode.PURGE_OFFLINE, true));
        purge.then(confirm(FiguraCacheCleanup.Mode.PURGE_OFFLINE));
        cleanup.then(purge);

        return cleanup;
    }

    private static LiteralArgumentBuilder<FiguraServerCommandSource> confirm(FiguraCacheCleanup.Mode mode) {
        LiteralArgumentBuilder<FiguraServerCommandSource> node = literal("confirm");
        node.executes(ctx -> execute(ctx.getSource(), mode, false));
        return node;
    }

    private static int execute(FiguraServerCommandSource source, FiguraCacheCleanup.Mode mode, boolean dryRun) {
        FiguraCacheCleanup.Report r = FiguraCacheCleanup.run(source.getServer(), mode, dryRun);

        String head = dryRun
                ? "FSB cleanup - preview (nothing was changed)"
                : "FSB cleanup - done";
        line(source, head, dryRun ? "yellow" : "green");

        if (mode == FiguraCacheCleanup.Mode.PURGE_OFFLINE) {
            line(source, "  mode: purge - every offline player record is included", "red");
        }

        line(source, "  avatars   %d (%s)".formatted(r.avatarsDeleted(), size(r.avatarBytesFreed())), "gray");
        line(source, "  orphan md %d (%s)".formatted(r.danglingDeleted(), size(r.danglingBytesFreed())), "gray");
        line(source, "  players   %d (%s)".formatted(r.usersDeleted(), size(r.userBytesFreed())), "gray");
        line(source, "  total     %d files, %s".formatted(r.totalDeleted(), size(r.totalBytesFreed())), "white");

        if (r.avatarsKeptProtected() > 0 || r.avatarsKeptInUse() > 0 || r.usersKeptOnline() > 0) {
            line(source, "  kept: %d protected, %d in use, %d online".formatted(
                    r.avatarsKeptProtected(), r.avatarsKeptInUse(), r.usersKeptOnline()), "gray");
        }

        for (String e: r.errors()) line(source, "  ! " + e, "red");

        if (dryRun && r.totalDeleted() > 0) {
            String cmd = mode == FiguraCacheCleanup.Mode.PURGE_OFFLINE
                    ? "/fsb cleanup purge confirm" : "/fsb cleanup confirm";
            line(source, "  run " + cmd + " to apply", "yellow");
        }
        return r.totalDeleted();
    }

    private static void line(FiguraServerCommandSource source, String text, String color) {
        source.sendComponent(ComponentUtils.text(text).color(color).build());
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return "%.1f KB".formatted(bytes / 1024.0);
        return "%.1f MB".formatted(bytes / (1024.0 * 1024.0));
    }
}
