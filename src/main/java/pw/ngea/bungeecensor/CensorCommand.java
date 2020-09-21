package pw.ngea.bungeecensor;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

/**
 *
 *
 * @author Pangea
 */
public class CensorCommand extends Command {

    private BungeeCensor plugin;

    public CensorCommand(BungeeCensor plugin) {
        super("censor");
        this.plugin = plugin;
    }


    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ProxiedPlayer)) {
            reload(sender);
            return;
        }
        ProxiedPlayer player = (ProxiedPlayer) sender;
        if (args.length == 0) {
            toggle(player);
        } else if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("enable")) {
            enable(player);
        } else if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("disable")) {
            disable(player);
        } else if (args[0].equalsIgnoreCase("toggle")) {
            toggle(player);
        } else if (args[0].equalsIgnoreCase("reload")) {
            reload(player);
        } else {
            if (sender.hasPermission("bungeecensor.reload")) {
                player.sendMessage(text(plugin.config.getString("messages.mod_usage")));
            } else {
                player.sendMessage(text(plugin.config.getString("messages.usage")));
            }
        }
    }

    public void reload(CommandSender sender) {
        if (!sender.hasPermission("bungeecensor.reload")) {
            sender.sendMessage(text(plugin.config.getString("messages.no_permission")));
            return;
        }
        if (plugin.loadConfig()) {
            sender.sendMessage(text(plugin.config.getString("messages.reload")));
        } else {
            sender.sendMessage(text(plugin.config.getString("messages.reload_fail")));
        }
    }

    public void toggle(ProxiedPlayer player) {
        if (!plugin.censorPlayers.remove(player.getUniqueId())) {
            plugin.censorPlayers.add(player.getUniqueId());
            player.sendMessage(text(plugin.config.getString("messages.enable")));
        } else {
            player.sendMessage(text(plugin.config.getString("messages.disable")));
        }
        if (!plugin.dirty.remove(player.getUniqueId())) {
            plugin.dirty.add(player.getUniqueId());
        }
    }

    public void enable(ProxiedPlayer player) {
        player.sendMessage(text(plugin.config.getString("messages.enable")));
        if (!plugin.censorPlayers.add(player.getUniqueId())) {
            return;
        }
        if (!plugin.dirty.remove(player.getUniqueId())) {
            plugin.dirty.add(player.getUniqueId());
        }
    }

    public void disable(ProxiedPlayer player) {
        player.sendMessage(text(plugin.config.getString("messages.disable")));
        if (!plugin.censorPlayers.remove(player.getUniqueId())) {
            return;
        }
        if (!plugin.dirty.remove(player.getUniqueId())) {
            plugin.dirty.add(player.getUniqueId());
        }
    }

    public static BaseComponent[] text(String str) {
        return TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', str));
    }

}
