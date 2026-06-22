package com.hungergames.loot;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.UUID;

public class LootBox {

    private final UUID id;
    private final Location location;
    private final String tier;
    private final Material blockType;
    private boolean opened;

    public LootBox(UUID id, Location location, String tier, Material blockType) {
        this.id = id;
        this.location = location;
        this.tier = tier;
        this.blockType = blockType;
    }

    public UUID getId() {
        return id;
    }

    public Location getLocation() {
        return location;
    }

    public String getTier() {
        return tier;
    }

    public Material getBlockType() {
        return blockType;
    }

    public boolean isOpened() {
        return opened;
    }

    public void setOpened(boolean opened) {
        this.opened = opened;
    }
}
