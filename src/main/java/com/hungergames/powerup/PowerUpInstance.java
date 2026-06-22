package com.hungergames.powerup;

import org.bukkit.Location;

import java.util.UUID;

public class PowerUpInstance {

    private final UUID instanceId;
    private final String definitionId;
    private UUID markerEntityId;
    private PowerUpState state = PowerUpState.FALLING;
    private long landedAtMillis;
    private long despawnAtMillis;
    private Location spawnLocation;
    private int beaconTick;

    public PowerUpInstance(String definitionId, Location spawnLocation) {
        this.instanceId = UUID.randomUUID();
        this.definitionId = definitionId;
        this.spawnLocation = spawnLocation.clone();
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public UUID getMarkerEntityId() {
        return markerEntityId;
    }

    public void setMarkerEntityId(UUID markerEntityId) {
        this.markerEntityId = markerEntityId;
    }

    public PowerUpState getState() {
        return state;
    }

    public void setState(PowerUpState state) {
        this.state = state;
    }

    public long getLandedAtMillis() {
        return landedAtMillis;
    }

    public void setLandedAtMillis(long landedAtMillis) {
        this.landedAtMillis = landedAtMillis;
    }

    public long getDespawnAtMillis() {
        return despawnAtMillis;
    }

    public void setDespawnAtMillis(long despawnAtMillis) {
        this.despawnAtMillis = despawnAtMillis;
    }

    public Location getSpawnLocation() {
        return spawnLocation;
    }

    public void setSpawnLocation(Location spawnLocation) {
        this.spawnLocation = spawnLocation.clone();
    }

    public int getBeaconTick() {
        return beaconTick;
    }

    public int incrementBeaconTick() {
        return ++beaconTick;
    }
}
