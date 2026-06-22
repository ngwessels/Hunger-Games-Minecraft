package com.hungergames.util;

import org.bukkit.Location;

import java.util.Collections;
import java.util.List;

public record SafeRespawnPlacement(Location location, List<BlockSnapshot> snapshots, boolean platformCreated) {

    public SafeRespawnPlacement {
        snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
    }

    public static SafeRespawnPlacement natural(Location location) {
        return new SafeRespawnPlacement(location, Collections.emptyList(), false);
    }

    public static SafeRespawnPlacement built(Location location, List<BlockSnapshot> snapshots) {
        return new SafeRespawnPlacement(location, snapshots, true);
    }
}
