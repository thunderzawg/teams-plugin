package dev.teams;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;

public class TeamsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private final Map<String, Team> teams = new LinkedHashMap<>();   // lowercase name -> team
    private final Map<UUID, Team> byPlayer = new HashMap<>();        // player -> their team
    private final Set<UUID> teamChat = new HashSet<>();              // players with secret team chat on
    private final Map<UUID, Invite> invites = new HashMap<>();       // target -> pending invite

    private record Invite(String team, long expires) {}

    private File dataFile;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "teams.yml");
        load();
        for (Team t : teams.values()) {
            syncScoreboard(t);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("team")).setExecutor(this);
        Objects.requireNonNull(getCommand("createteam")).setExecutor(this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            refreshGlow(p);
        }
    }

    // ---------------------------------------------------------------- storage

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        var section = yml.getConfigurationSection("teams");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String path = "teams." + key + ".";
            NamedTextColor color = NamedTextColor.NAMES.value(yml.getString(path + "color", "white"));
            UUID owner = UUID.fromString(Objects.requireNonNull(yml.getString(path + "owner")));
            Team t = new Team(yml.getString(path + "name", key), color == null ? NamedTextColor.WHITE : color, owner);
            t.pvp = yml.getBoolean(path + "pvp", false);
            for (String m : yml.getStringList(path + "members")) {
                t.members.add(UUID.fromString(m));
            }
            teams.put(key.toLowerCase(), t);
            for (UUID id : t.members) byPlayer.put(id, t);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Team t : teams.values()) {
            String path = "teams." + t.name.toLowerCase() + ".";
            yml.set(path + "name", t.name);
            yml.set(path + "color", NamedTextColor.NAMES.key(t.color));
            yml.set(path + "owner", t.owner.toString());
            yml.set(path + "pvp", t.pvp);
            yml.set(path + "members", t.members.stream().map(UUID::toString).collect(Collectors.toList()));
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save teams.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ scoreboard

    /** Makes sure the vanilla scoreboard team exists; this is what colors the glow + name tag. */
    private org.bukkit.scoreboard.Team syncScoreboard(Team t) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String id = "tm_" + t.name.toLowerCase();
        org.bukkit.scoreboard.Team st = sb.getTeam(id);
        if (st == null) st = sb.registerNewTeam(id);
        st.color(t.color);
        st.prefix(Component.text("[" + t.name + "] ", t.color));
        for (UUID u : t.members) {
            String n = nameOf(u);
            if (n != null) st.addEntry(n);
        }
        return st;
    }

    private void refreshGlow(Player p) {
        p.setGlowing(byPlayer.containsKey(p.getUniqueId()));
    }

    // -------------------------------------------------------------- helpers

    private String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName();
    }

    private void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    private void err(CommandSender s, String text) {
        msg(s, text, NamedTextColor.RED);
    }

    private void sendToTeam(Team t, Component c) {
        for (UUID u : t.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(c);
        }
    }

    private void addMember(Team t, Player p) {
        t.members.add(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), t);
        syncScoreboard(t).addEntry(p.getName());
        p.setGlowing(true);
        save();
    }

    private void removeMember(Team t, UUID id, String name) {
        t.members.remove(id);
        byPlayer.remove(id);
        teamChat.remove(id);
        org.bukkit.scoreboard.Team st = Bukkit.getScoreboardManager().getMainScoreboard()
                .getTeam("tm_" + t.name.toLowerCase());
        if (st != null && name != null) st.removeEntry(name);
        Player online = Bukkit.getPlayer(id);
        if (online != null) online.setGlowing(false);
        save();
    }

    // ------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use team commands.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("createteam")) {
            String[] n = new String[args.length + 1];
            n[0] = "create";
            System.arraycopy(args, 0, n, 1, args.length);
            args = n;
        }
        if (args.length == 0) {
            help(p);
            return true;
        }
        Team mine = byPlayer.get(p.getUniqueId());

        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (args.length < 3) {
                    err(p, "Usage: /team create <name> <color>");
                    return true;
                }
                if (mine != null) {
                    err(p, "You are already in a team. Leave or disband it first.");
                    return true;
                }
                String name = args[1];
                if (!name.matches("[A-Za-z0-9_]{3,12}")) {
                    err(p, "Team name must be 3-12 letters, numbers or underscores.");
                    return true;
                }
                if (teams.containsKey(name.toLowerCase())) {
                    err(p, "That team name is taken.");
                    return true;
                }
                NamedTextColor color = NamedTextColor.NAMES.value(args[2].toLowerCase());
                if (color == null) {
                    err(p, "Unknown color. Use one of: " + String.join(", ", NamedTextColor.NAMES.keys()));
                    return true;
                }
                Team t = new Team(name, color, p.getUniqueId());
                teams.put(name.toLowerCase(), t);
                byPlayer.put(p.getUniqueId(), t);
                syncScoreboard(t);
                p.setGlowing(true);
                save();
                p.sendMessage(Component.text("Team ", NamedTextColor.GREEN)
                        .append(Component.text(name, color))
                        .append(Component.text(" created. You are the owner.", NamedTextColor.GREEN)));
            }
            case "invite" -> {
                if (!requireOwner(p, mine)) return true;
                if (args.length < 2) {
                    err(p, "Usage: /team invite <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    err(p, "That player is not online.");
                    return true;
                }
                if (byPlayer.containsKey(target.getUniqueId())) {
                    err(p, target.getName() + " is already in a team.");
                    return true;
                }
                invites.put(target.getUniqueId(), new Invite(mine.name, System.currentTimeMillis() + 60_000));
                msg(p, "Invite sent to " + target.getName() + " (expires in 60s).", NamedTextColor.GREEN);
                target.sendMessage(Component.text(p.getName() + " invited you to team ", NamedTextColor.YELLOW)
                        .append(Component.text(mine.name, mine.color))
                        .append(Component.text(". ", NamedTextColor.YELLOW))
                        .append(Component.text("[Click to accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/team accept"))));
            }
            case "accept" -> {
                Invite inv = invites.remove(p.getUniqueId());
                if (inv == null || inv.expires() < System.currentTimeMillis()) {
                    err(p, "You have no pending invite.");
                    return true;
                }
                if (mine != null) {
                    err(p, "You are already in a team.");
                    return true;
                }
                Team t = teams.get(inv.team().toLowerCase());
                if (t == null) {
                    err(p, "That team no longer exists.");
                    return true;
                }
                addMember(t, p);
                sendToTeam(t, Component.text(p.getName() + " joined the team!", t.color));
            }
            case "leave" -> {
                if (mine == null) {
                    err(p, "You are not in a team.");
                    return true;
                }
                if (mine.owner.equals(p.getUniqueId())) {
                    err(p, "Owners can't leave. Use /team disband " + mine.name + " instead.");
                    return true;
                }
                removeMember(mine, p.getUniqueId(), p.getName());
                msg(p, "You left " + mine.name + ".", NamedTextColor.YELLOW);
                sendToTeam(mine, Component.text(p.getName() + " left the team.", mine.color));
            }
            case "kick" -> {
                if (!requireOwner(p, mine)) return true;
                if (args.length < 2) {
                    err(p, "Usage: /team kick <player>");
                    return true;
                }
                UUID found = null;
                String foundName = null;
                for (UUID u : mine.members) {
                    String n = nameOf(u);
                    if (n != null && n.equalsIgnoreCase(args[1])) {
                        found = u;
                        foundName = n;
                    }
                }
                if (found == null) {
                    err(p, "That player is not in your team.");
                    return true;
                }
                if (found.equals(p.getUniqueId())) {
                    err(p, "You can't kick yourself. Use /team disband " + mine.name + ".");
                    return true;
                }
                removeMember(mine, found, foundName);
                msg(p, foundName + " was kicked.", NamedTextColor.YELLOW);
                Player kicked = Bukkit.getPlayer(found);
                if (kicked != null) err(kicked, "You were kicked from team " + mine.name + ".");
            }
            case "disband" -> {
                if (args.length < 2) {
                    err(p, "Usage: /team disband <teamname>");
                    return true;
                }
                Team t = teams.get(args[1].toLowerCase());
                if (t == null) {
                    err(p, "No team with that name.");
                    return true;
                }
                boolean isOwner = t.owner.equals(p.getUniqueId());
                if (!isOwner && !p.hasPermission("teams.admin")) {
                    err(p, "Only the team owner can disband it.");
                    return true;
                }
                sendToTeam(t, Component.text("Team " + t.name + " was disbanded.", NamedTextColor.RED));
                for (UUID u : new ArrayList<>(t.members)) {
                    removeMember(t, u, nameOf(u));
                }
                org.bukkit.scoreboard.Team st = Bukkit.getScoreboardManager().getMainScoreboard()
                        .getTeam("tm_" + t.name.toLowerCase());
                if (st != null) st.unregister();
                teams.remove(t.name.toLowerCase());
                save();
                if (!isOwner) msg(p, "Team " + t.name + " disbanded.", NamedTextColor.YELLOW);
            }
            case "info" -> {
                Team t = args.length >= 2 ? teams.get(args[1].toLowerCase()) : mine;
                if (t == null) {
                    err(p, args.length >= 2 ? "No team with that name." : "You are not in a team. Use /team info <name>.");
                    return true;
                }
                p.sendMessage(Component.text("=== Team ", NamedTextColor.GRAY)
                        .append(Component.text(t.name, t.color))
                        .append(Component.text(" ===", NamedTextColor.GRAY)));
                p.sendMessage(Component.text("Owner: ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(nameOf(t.owner)), t.color)));
                p.sendMessage(Component.text("Color: ", NamedTextColor.GRAY)
                        .append(Component.text(NamedTextColor.NAMES.key(t.color), t.color)));
                p.sendMessage(Component.text("Team PvP: ", NamedTextColor.GRAY)
                        .append(Component.text(t.pvp ? "enabled" : "disabled", t.pvp ? NamedTextColor.RED : NamedTextColor.GREEN)));
                p.sendMessage(Component.text("Members (" + t.members.size() + "):", NamedTextColor.GRAY));
                for (UUID u : t.members) {
                    boolean online = Bukkit.getPlayer(u) != null;
                    p.sendMessage(Component.text(" - ", NamedTextColor.DARK_GRAY)
                            .append(Component.text(String.valueOf(nameOf(u)), t.color))
                            .append(Component.text(online ? " (online)" : " (offline)", NamedTextColor.DARK_GRAY)));
                }
            }
            case "pvp" -> {
                if (!requireOwner(p, mine)) return true;
                if (args.length < 2 || !(args[1].equalsIgnoreCase("enable") || args[1].equalsIgnoreCase("disable"))) {
                    err(p, "Usage: /team pvp <enable|disable>");
                    return true;
                }
                mine.pvp = args[1].equalsIgnoreCase("enable");
                save();
                sendToTeam(mine, Component.text("Team PvP is now " + (mine.pvp ? "enabled" : "disabled") + ".", mine.color));
            }
            case "chat" -> {
                if (mine == null) {
                    err(p, "You are not in a team.");
                    return true;
                }
                if (args.length < 2 || !(args[1].equalsIgnoreCase("enable") || args[1].equalsIgnoreCase("disable"))) {
                    err(p, "Usage: /team chat <enable|disable>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("enable")) {
                    teamChat.add(p.getUniqueId());
                    msg(p, "Team chat ON: your messages now go only to your team.", NamedTextColor.GREEN);
                } else {
                    teamChat.remove(p.getUniqueId());
                    msg(p, "Team chat OFF: your messages go to everyone again.", NamedTextColor.YELLOW);
                }
            }
            default -> help(p);
        }
        return true;
    }

    private boolean requireOwner(Player p, Team t) {
        if (t == null) {
            err(p, "You are not in a team.");
            return false;
        }
        if (!t.owner.equals(p.getUniqueId())) {
            err(p, "Only the team owner can do that.");
            return false;
        }
        return true;
    }

    private void help(Player p) {
        String[] lines = {
                "/team create <name> <color>",
                "/team invite <player>   (owner)",
                "/team accept | /team leave",
                "/team kick <player>   (owner)",
                "/team disband <teamname>   (owner)",
                "/team info [teamname]",
                "/team pvp <enable|disable>   (owner)",
                "/team chat <enable|disable>"
        };
        for (String l : lines) msg(p, l, NamedTextColor.GOLD);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("createteam")) {
            if (args.length == 2) out.addAll(NamedTextColor.NAMES.keys());
        } else if (args.length == 1) {
            out.addAll(List.of("create", "invite", "accept", "leave", "kick", "disband", "info", "pvp", "chat"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "invite" -> Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
                case "kick" -> {
                    if (sender instanceof Player p && byPlayer.containsKey(p.getUniqueId())) {
                        for (UUID u : byPlayer.get(p.getUniqueId()).members) out.add(String.valueOf(nameOf(u)));
                    }
                }
                case "pvp", "chat" -> out.addAll(List.of("enable", "disable"));
                case "disband", "info" -> teams.values().forEach(t -> out.add(t.name));
                default -> { }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            out.addAll(NamedTextColor.NAMES.keys());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        return out.stream().filter(s -> s.toLowerCase().startsWith(last)).sorted().collect(Collectors.toList());
    }

    // ------------------------------------------------------------ listeners

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Team t = byPlayer.get(p.getUniqueId());
        if (t != null) syncScoreboard(t).addEntry(p.getName());
        refreshGlow(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        teamChat.remove(e.getPlayer().getUniqueId());
        invites.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) {
            attacker = p;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;
        Team a = byPlayer.get(attacker.getUniqueId());
        Team v = byPlayer.get(victim.getUniqueId());
        if (a != null && a == v && !a.pvp) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Team t = byPlayer.get(p.getUniqueId());
        if (t == null) return;
        boolean secret = teamChat.contains(p.getUniqueId());

        if (secret) {
            e.viewers().removeIf(a -> !(a instanceof ConsoleCommandSender)
                    && !(a instanceof Player pl && t.members.contains(pl.getUniqueId())));
        }

        e.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> {
            Component prefix = secret
                    ? Component.text("[Team] ", t.color)
                    : Component.empty();
            return Component.textOfChildren(
                    prefix,
                    Component.text(source.getName(), t.color),
                    Component.text(": ", NamedTextColor.GRAY),
                    message.colorIfAbsent(t.color));
        }));
    }
}
