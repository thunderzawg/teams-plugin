package dev.teams;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;

public class Team {
    public final String name;
    public NamedTextColor color;
    public UUID owner;
    public boolean pvp = false; // friendly fire between members, off by default
    public final Set<UUID> members = new LinkedHashSet<>();

    public Team(String name, NamedTextColor color, UUID owner) {
        this.name = name;
        this.color = color;
        this.owner = owner;
        this.members.add(owner);
    }
}
