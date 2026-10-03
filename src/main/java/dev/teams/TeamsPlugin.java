package dev.teams;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class TeamsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String CROWN = "\u265B"; // small crown symbol
    private static final int HOME_DELAY_SECONDS = 5;

    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, Team> byPlayer = new HashMap<>();
    private final Set<UUID> teamChat = new HashSet<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<UUID, HomeTeleport> pendingHome = new HashMap<>();
    private File dataFile;

    private record Invite(String team, long expires) {}

    private static final class HomeTeleport {
        final BukkitTask task;
        final int bx, by, bz;
        HomeTeleport(BukkitTask task, int bx, int by, int bz) {
            this.task = task;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "teams.yml");
        load();
        for (Team t : teams.values()) syncScoreboard(t);
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("team")).setExecutor(this);
        Objects.requireNonNull(getCommand("createteam")).setExecutor(this);
        for (Player p : Bukkit.getOnlinePlayers()) refreshGlow(p);
    }

    @Override
    public void onDisable() {
        for (HomeTeleport h : pendingHome.values()) h.task.cancel();
        pendingHome.clear();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("teams");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            String base = "teams." + key + ".";
            String name = y.getString(base + "name", key);
            NamedTextColor color = NamedTextColor.NAMES.value(y.getString(base + "color", "white"));
            UUID owner = UUID.fromString(Objects.requireNonNull(y.getString(base + "owner")));
            Team t = new Team(name, color == null ? NamedTextColor.WHITE : color, owner);
            t.pvp = y.getBoolean(base + "pvp", true);
            for (String m : y.getStringList(base + "members")) t.members.add(UUID.fromString(m));
            String hw = y.getString(base + "home.world");
            if (hw != null) {
                t.homeWorld = hw;
                t.homeX = y.getDouble(base + "home.x");
                t.homeY = y.getDouble(base + "home.y");
                t.homeZ = y.getDouble(base + "home.z");
                t.homeYaw = (float) y.getDouble(base + "home.yaw");
                t.homePitch = (float) y.getDouble(base + "home.pitch");
            }
            teams.put(key.toLowerCase(), t);
            for (UUID m : t.members) byPlayer.put(m, t);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Team t : teams.values()) {
            String base = "teams." + t.name.toLowerCase() + ".";
            y.set(base + "name", t.name);
            y.set(base + "color", NamedTextColor.NAMES.key(t.color));
            y.set(base + "owner", t.owner.toString());
            y.set(base + "pvp", t.pvp);
            y.set(base + "members", t.members.stream().map(UUID::toString).collect(Collectors.toList()));
            if (t.hasHome()) {
                y.set(base + "home.world", t.homeWorld);
                y.set(base + "home.x", t.homeX);
                y.set(base + "home.y", t.homeY);
                y.set(base + "home.z", t.homeZ);
                y.set(base + "home.yaw", t.homeYaw);
                y.set(base + "home.pitch", t.homePitch);
            }
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save teams.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ scoreboard (color, glow, owner crown)

    private static String memberTeamId(Team t) {
        return "tm_" + t.name.toLowerCase();
    }

    private static String ownerTeamId(Team t) {
        return "to_" + t.name.toLowerCase();
    }

    private org.bukkit.scoreboard.Team getOrCreate(Scoreboard sb, String id) {
        org.bukkit.scoreboard.Team st = sb.getTeam(id);
        if (st == null) st = sb.registerNewTeam(id);
        return st;
    }

    /** Makes sure the scoreboard teams exist and every member is in the right one (owner gets a crown prefix). */
    private void syncScoreboard(Team t) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team members = getOrCreate(sb, memberTeamId(t));
        org.bukkit.scoreboard.Team owner = getOrCreate(sb, ownerTeamId(t));

        members.color(t.color);
        members.prefix(Component.text("[" + t.name + "]", t.color));

        owner.color(t.color);
        owner.prefix(Component.text(CROWN + " ", NamedTextColor.GOLD)
                .append(Component.text("[" + t.name + "]", t.color)));

        for (UUID m : t.members) {
            String n = nameOf(m);
            if (n == null) continue;
            if (m.equals(t.owner)) owner.addEntry(n);
            else members.addEntry(n);
        }
    }

    private void unregisterScoreboard(Team t) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        org.bukkit.scoreboard.Team a = sb.getTeam(memberTeamId(t));
        if (a != null) a.unregister();
        org.bukkit.scoreboard.Team b = sb.getTeam(ownerTeamId(t));
        if (b != null) b.unregister();
    }

    private void refreshGlow(Player p) {
        p.setGlowing(byPlayer.containsKey(p.getUniqueId()));
    }

    // ------------------------------------------------------------------ helpers

    private String nameOf(UUID id) {
        return Bukkit.getOfflinePlayer(id).getName();
    }

    private void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    private void err(CommandSender to, String text) {
        msg(to, text, NamedTextColor.RED);
    }

    private void sendToTeam(Team t, Component c) {
        for (UUID m : t.members) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) p.sendMessage(c);
        }
    }

    private void addMember(Team t, Player p) {
        t.members.add(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), t);
        syncScoreboard(t);
        p.setGlowing(true);
        save();
    }

    private void removeMember(Team t, UUID id, String name) {
        t.members.remove(id);
        byPlayer.remove(id);
        teamChat.remove(id);
        cancelHome(id, null);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        if (name != null) {
            org.bukkit.scoreboard.Team a = sb.getTeam(memberTeamId(t));
            if (a != null) a.removeEntry(name);
            org.bukkit.scoreboard.Team b = sb.getTeam(ownerTeamId(t));
            if (b != null) b.removeEntry(name);
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) p.setGlowing(false);
        save();
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
                "/team chat <enable|disable>",
                "/team create home   (owner)",
                "/team delete home   (owner)",
                "/team home"
        };
        for (String l : lines) msg(p, l, NamedTextColor.GOLD);
    }

    // ------------------------------------------------------------------ team home

    private void cancelHome(UUID id, Player notify) {
        HomeTeleport h = pendingHome.remove(id);
        if (h != null) {
            h.task.cancel();
            if (notify != null) {
                notify.sendActionBar(Component.text("Teleport cancelled.", NamedTextColor.RED));
                err(notify, "Teleport cancelled.");
            }
        }
    }

    private void startHomeTeleport(Player p, Team t) {
        if (!t.hasHome()) {
            err(p, "Your team has no home yet. The owner can set one with /team create home.");
            return;
        }
        if (pendingHome.containsKey(p.getUniqueId())) {
            err(p, "You are already teleporting.");
            return;
        }
        Location l = p.getLocation();
        UUID id = p.getUniqueId();
        msg(p, "Teleporting to team home in " + HOME_DELAY_SECONDS + " seconds. Don't move!", NamedTextColor.YELLOW);
        p.sendActionBar(Component.text("Teleporting in " + HOME_DELAY_SECONDS + "...", NamedTextColor.YELLOW));

        int[] left = {HOME_DELAY_SECONDS};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            Player pl = Bukkit.getPlayer(id);
            if (pl == null || !pl.isOnline()) {
                cancelHome(id, null);
                return;
            }
            left[0]--;
            if (left[0] > 0) {
                pl.sendActionBar(Component.text("Teleporting in " + left[0] + "...", NamedTextColor.YELLOW));
                return;
            }
            HomeTeleport running = pendingHome.remove(id);
            if (running != null) running.task.cancel();
            Team cur = byPlayer.get(id);
            if (cur == null || !cur.hasHome()) {
                err(pl, "Your team home no longer exists.");
                return;
            }
            World w = Bukkit.getWorld(cur.homeWorld);
            if (w == null) {
                err(pl, "The home world is not loaded.");
                return;
            }
            pl.teleport(new Location(w, cur.homeX, cur.homeY, cur.homeZ, cur.homeYaw, cur.homePitch));
            pl.sendActionBar(Component.text("Teleported to team home!", NamedTextColor.GREEN));
            msg(pl, "Teleported to team home!", NamedTextColor.GREEN);
        }, 20L, 20L);
        pendingHome.put(id, new HomeTeleport(task, l.getBlockX(), l.getBlockY(), l.getBlockZ()));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use team commands.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("createteam")) {
            String[] shifted = new String[args.length + 1];
            shifted[0] = "create";
            System.arraycopy(args, 0, shifted, 1, args.length);
            args = shifted;
        }
        if (args.length == 0) {
            help(p);
            return true;
        }
        Team mine = byPlayer.get(p.getUniqueId());
        String sub = args[0].toLowerCase();

        switch (sub) {
            case "create" -> {
                // /team create home  -> set team home (owner only)
                if (args.length == 2 && args[1].equalsIgnoreCase("home")) {
                    if (!requireOwner(p, mine)) return true;
                    Location l = p.getLocation();
                    boolean had = mine.hasHome();
                    mine.homeWorld = l.getWorld().getName();
                    mine.homeX = l.getX();
                    mine.homeY = l.getY();
                    mine.homeZ = l.getZ();
                    mine.homeYaw = l.getYaw();
                    mine.homePitch = l.getPitch();
                    save();
                    sendToTeam(mine, Component.text(had ? "Team home was moved." : "Team home was set.", mine.color));
                    return true;
                }
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
                    err(p, "Unknown color. Use one of: " + String.join(",", NamedTextColor.NAMES.keys()));
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
            case "delete" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("home")) {
                    err(p, "Usage: /team delete home");
                    return true;
                }
                if (!requireOwner(p, mine)) return true;
                if (!mine.hasHome()) {
                    err(p, "Your team has no home.");
                    return true;
                }
                mine.clearHome();
                save();
                sendToTeam(mine, Component.text("Team home was deleted.", NamedTextColor.RED));
            }
            case "home" -> {
                if (mine == null) {
                    err(p, "You are not in a team.");
                    return true;
                }
                startHomeTeleport(p, mine);
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
                invites.put(target.getUniqueId(), new Invite(mine.name, System.currentTimeMillis() + 60000L));
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
                for (UUID m : mine.members) {
                    String n = nameOf(m);
                    if (n != null && n.equalsIgnoreCase(args[1])) {
                        found = m;
                        foundName = n;
                        break;
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
                if (!t.owner.equals(p.getUniqueId()) && !p.hasPermission("teams.admin")) {
                    err(p, "Only the team owner can disband it.");
                    return true;
                }
                boolean wasMember = t.members.contains(p.getUniqueId());
                sendToTeam(t, Component.text("Team " + t.name + " was disbanded.", NamedTextColor.RED));
                for (UUID m : new ArrayList<>(t.members)) removeMember(t, m, nameOf(m));
                unregisterScoreboard(t);
                teams.remove(t.name.toLowerCase());
                save();
                if (!wasMember) msg(p, "Team " + t.name + " disbanded.", NamedTextColor.YELLOW);
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
                        .append(Component.text(String.valueOf(NamedTextColor.NAMES.key(t.color)), t.color)));
                p.sendMessage(Component.text("Team PvP: ", NamedTextColor.GRAY)
                        .append(Component.text(t.pvp ? "enabled" : "disabled",
                                t.pvp ? NamedTextColor.RED : NamedTextColor.GREEN)));
                p.sendMessage(Component.text("Members (" + t.members.size() + "):", NamedTextColor.GRAY));
                for (UUID m : t.members) {
                    boolean online = Bukkit.getPlayer(m) != null;
                    p.sendMessage(Component.text(" - ", NamedTextColor.DARK_GRAY)
                            .append(Component.text(String.valueOf(nameOf(m)), t.color))
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

    // ------------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p)) return out;
        if (cmd.getName().equalsIgnoreCase("createteam")) {
            if (args.length == 2) out.addAll(NamedTextColor.NAMES.keys());
        } else if (args.length == 1) {
            out.addAll(List.of("create", "delete", "home", "invite", "accept", "leave", "kick", "disband", "info", "pvp", "chat"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "invite" -> Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
                case "kick" -> {
                    Team t = byPlayer.get(p.getUniqueId());
                    if (t != null) for (UUID m : t.members) out.add(String.valueOf(nameOf(m)));
                }
                case "pvp", "chat" -> out.addAll(List.of("enable", "disable"));
                case "disband", "info" -> teams.values().forEach(t -> out.add(t.name));
                case "create", "delete" -> out.add("home");
                default -> { }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            out.addAll(NamedTextColor.NAMES.keys());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        return out.stream().filter(s -> s.toLowerCase().startsWith(last)).sorted().collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Team t = byPlayer.get(p.getUniqueId());
        if (t != null) syncScoreboard(t);
        refreshGlow(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        teamChat.remove(id);
        invites.remove(id);
        cancelHome(id, null);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player a) attacker = a;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s) attacker = s;
        if (attacker == null || attacker.equals(victim)) return;
        Team t1 = byPlayer.get(victim.getUniqueId());
        Team t2 = byPlayer.get(attacker.getUniqueId());
        if (t1 != null && t1 == t2 && !t1.pvp) e.setCancelled(true);
    }

    // cancel a pending /team home teleport if the player takes damage or moves
    @EventHandler
    public void onAnyDamage(EntityDamageEvent e) {
        if (e.isCancelled()) return;
        if (e.getEntity() instanceof Player p && pendingHome.containsKey(p.getUniqueId())) {
            cancelHome(p.getUniqueId(), p);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        HomeTeleport h = pendingHome.get(e.getPlayer().getUniqueId());
        if (h == null || e.getTo() == null) return;
        Location to = e.getTo();
        if (to.getBlockX() != h.bx || to.getBlockY() != h.by || to.getBlockZ() != h.bz) {
            cancelHome(e.getPlayer().getUniqueId(), e.getPlayer());
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Team team = byPlayer.get(p.getUniqueId());
        if (team == null) return;
        boolean teamMode = teamChat.contains(p.getUniqueId());
        if (teamMode) {
            e.viewers().removeIf(a -> !(a instanceof ConsoleCommandSender)
                    && !(a instanceof Player pl && team.members.contains(pl.getUniqueId())));
        }
        boolean isOwner = team.owner.equals(p.getUniqueId());
        e.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> {
            Component tag = teamMode ? Component.text("[Team] ", team.color) : Component.empty();
            Component crown = isOwner ? Component.text(CROWN + " ", NamedTextColor.GOLD) : Component.empty();
            // team chat messages are yellow, normal chat keeps the team color
            NamedTextColor msgColor = teamMode ? NamedTextColor.YELLOW : team.color;
            return Component.textOfChildren(
                    tag,
                    crown,
                    Component.text(source.getName(), team.color),
                    Component.text(": ", NamedTextColor.GRAY),
                    message.colorIfAbsent(msgColor));
        }));
    }
}
