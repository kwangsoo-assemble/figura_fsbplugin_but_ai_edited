package org.figuramc.figura.server.commands;


import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.figuramc.figura.server.FiguraPermissionNodes;
import org.figuramc.figura.server.FiguraServer;
import org.figuramc.figura.server.utils.ComponentUtils;

import java.util.function.Predicate;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;

public class FiguraServerCommands {

    public static LiteralArgumentBuilder<FiguraServerCommandSource> getCommand() {
        LiteralArgumentBuilder<FiguraServerCommandSource> root = literal("fsb");
        root.then(FiguraAvatarCommand.getCommand());
        root.then(FiguraBadgesCommand.getCommand());
        root.then(FiguraCleanupCommand.getCommand());

        LiteralArgumentBuilder<FiguraServerCommandSource> reload = literal("reload");
        reload.requires(permissionCheck(FiguraPermissionNodes.FIGURA_RELOAD));
        reload.executes(ctx -> {
            FiguraServerCommandSource source = ctx.getSource();
            source.getServer().reloadConfig();
            source.sendComponent(ComponentUtils.text("FSB config reloaded.").color("green").build());
            return 1;
        });
        root.then(reload);

        return root;
    }

    public static class PermissionPredicate implements Predicate<FiguraServerCommandSource> {
        public final FiguraPermissionNodes permission;

        public PermissionPredicate(FiguraPermissionNodes permission) {
            this.permission = permission;
        }

        @Override
        public boolean test(FiguraServerCommandSource source) {
            try {
                return source.permission(permission.toString());
            }
            catch (Exception e) {
                FiguraServer.getInstance().logError("Error occured while processing permission check: ", e);
                return false;
            }
        }
    }

    public static PermissionPredicate permissionCheck(FiguraPermissionNodes permission) {
        return new PermissionPredicate(permission);
    }
}
