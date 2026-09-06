package com.hungergames.arena;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * Transient per-arena settlement data produced during generation and consumed
 * by SettlementManager at match start. Not persisted; regenerated identically
 * from the arena terrain seed on every build.
 */
public class SettlementLayout {

    /** A loot chest embedded in a generated building. */
    public record ChestSite(int x, int y, int z) {
        public Location toLocation(WorldResolver resolver) {
            return resolver.resolve(this);
        }

        @FunctionalInterface
        public interface WorldResolver {
            Location resolve(ChestSite site);
        }
    }

    /** Ground-level anchor for a building whose mobs spawn there. */
    public record StructureSite(int x, int y, int z, Kind kind) {
        public enum Kind { OUTPOST, VILLAGE }
    }

    /** Interior bounds of a church; PvP in or into the nave condemns the aggressor. */
    public record ChurchSite(int minX, int minY, int minZ,
                             int maxX, int maxY, int maxZ) {
        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX + 1
                    && y >= minY && y <= maxY + 1
                    && z >= minZ && z <= maxZ + 1;
        }
    }

    private final List<ChestSite> chestSites = new ArrayList<>();
    private final List<StructureSite> mobSites = new ArrayList<>();
    private final List<ChurchSite> churches = new ArrayList<>();

    public void addChest(int x, int y, int z) {
        chestSites.add(new ChestSite(x, y, z));
    }

    public void addMobSite(int x, int y, int z, StructureSite.Kind kind) {
        mobSites.add(new StructureSite(x, y, z, kind));
    }

    public void addChurch(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        churches.add(new ChurchSite(minX, minY, minZ, maxX, maxY, maxZ));
    }

    public List<ChestSite> getChestSites() {
        return chestSites;
    }

    public List<StructureSite> getMobSites() {
        return mobSites;
    }

    public List<ChurchSite> getChurches() {
        return churches;
    }

    public void clear() {
        chestSites.clear();
        mobSites.clear();
        churches.clear();
    }
}
