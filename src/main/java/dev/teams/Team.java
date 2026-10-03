package dev.teams;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class Team {
    public final String name;
    public NamedTextColor color;
    public UUID owner;
    public boolean pvp = true;
    public final Set<UUID> members = new LinkedHashSet<>();

    // Team home (null world = no home set)
    public String homeWorld;
    public double homeX, homeY, homeZ;
    public float homeYaw, homePitch;

    public Team(String name, NamedTextColor color, UUID owner) {
        this.name = name;
        this.color = color;
        this.owner = owner;
        this.members.add(owner);
    }

    public boolean hasHome() {
        return homeWorld != null;
    }

    public void clearHome() {
        homeWorld = null;
    }
}
