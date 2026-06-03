package com.mtxgdn.common.command;

import com.mtxgdn.permission.PermissionService;

public abstract class Command {

    private final String[] names;
    private final String description;
    private final String usage;
    private final String category;
    private final String permission;
    private final boolean privateOnly;

    protected Command(String[] names, String description, String usage,
                      String category, String permission, boolean privateOnly) {
        this.names = names;
        this.description = description;
        this.usage = usage;
        this.category = category;
        this.permission = permission;
        this.privateOnly = privateOnly;
        CommandRegistry.register(this);
    }

    protected Command(String[] names, String description, String usage,
                      String category, String permission) {
        this(names, description, usage, category, permission, false);
    }

    protected Command(String[] names, String description, String usage, String category) {
        this(names, description, usage, category, null, false);
    }

    public String[] getNames() {
        return names;
    }

    public String getDescription() {
        return description;
    }

    public String getUsage() {
        return usage;
    }

    public String getCategory() {
        return category;
    }

    public String getPermission() {
        return permission;
    }

    public boolean isPrivateOnly() {
        return privateOnly;
    }

    public boolean shouldShowInHelp(Long userId) {
        if (permission == null) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        return PermissionService.hasPermission(userId, permission);
    }

    public abstract void execute(CommandContext ctx);
}
