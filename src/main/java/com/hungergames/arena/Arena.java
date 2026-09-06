package com.hungergames.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

public class Arena {

    private final String name;
    private String worldName;
    private double centerX;
    private double centerY;
    private double centerZ;
    private int radius;
    private int wallHeight;
    private String gameModeId;
    private boolean generated;
    private int terrainSeed;
    private final List<Location> spawnPoints = new ArrayList<>();
    private Location centerSpawn;
    /** Transient settlement data (not persisted): chest sites + mob spawn points. */
    private final SettlementLayout settlementLayout = new SettlementLayout();
    private final ArcticLayout arcticLayout = new ArcticLayout();

    public static final double DEFAULT_CENTER_X = 0;
    public static final double DEFAULT_CENTER_Y = 20;
    public static final double DEFAULT_CENTER_Z = 0;
    /** Default playable radius for modes that omit arenaRadius. */
    public static final int RADIUS = 300;
    /** Fixed arena wall height in blocks; not configurable per arena. */
    public static final int WALL_HEIGHT = 20;
    /** Previous default before terrain height reduction; used for arena migration. */
    public static final double LEGACY_CENTER_Y = 64;

    public Arena(String name) {
        this.name = name;
        this.worldName = "hg_" + name.toLowerCase();
        this.centerX = DEFAULT_CENTER_X;
        this.centerY = DEFAULT_CENTER_Y;
        this.centerZ = DEFAULT_CENTER_Z;
        this.radius = RADIUS;
        this.wallHeight = WALL_HEIGHT;
        this.gameModeId = "standard";
        this.terrainSeed = name.hashCode();
    }

    public String getName() {
        return name;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    public double getCenterZ() {
        return centerZ;
    }

    public void setCenter(double x, double y, double z) {
        this.centerX = x;
        this.centerY = y;
        this.centerZ = z;
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Math.max(20, radius);
    }

    public int getWallHeight() {
        return wallHeight;
    }

    public void setWallHeight(int ignored) {
        this.wallHeight = WALL_HEIGHT;
    }

    public String getGameModeId() {
        return gameModeId;
    }

    public void setGameModeId(String gameModeId) {
        this.gameModeId = gameModeId;
    }

    public boolean isGenerated() {
        return generated;
    }

    public void setGenerated(boolean generated) {
        this.generated = generated;
    }

    public int getTerrainSeed() {
        return terrainSeed;
    }

    public void setTerrainSeed(int terrainSeed) {
        this.terrainSeed = terrainSeed;
    }

    public List<Location> getSpawnPoints() {
        return spawnPoints;
    }

    public void setSpawnPoints(List<Location> points) {
        spawnPoints.clear();
        spawnPoints.addAll(points);
    }

    public Location getCenterSpawn() {
        return centerSpawn;
    }

    public void setCenterSpawn(Location centerSpawn) {
        this.centerSpawn = centerSpawn;
    }

    public SettlementLayout getSettlementLayout() {
        return settlementLayout;
    }

    public ArcticLayout getArcticLayout() {
        return arcticLayout;
    }

    public Location getCenterLocation() {
        World world = getWorld();
        if (world == null) {
            return null;
        }
        return new Location(world, centerX, centerY, centerZ);
    }

    public World getWorld() {
        return Bukkit.getWorld(worldName);
    }

    public boolean hasCenter() {
        return true;
    }

    public void resetCenterToDefault() {
        centerX = DEFAULT_CENTER_X;
        centerY = DEFAULT_CENTER_Y;
        centerZ = DEFAULT_CENTER_Z;
    }
}
