package com.hungergames.arena;

import java.util.ArrayList;
import java.util.List;

/**
 * Transient arctic feature sites (glaciers + polar bears), recomputed from seed.
 */
public class ArcticLayout {

    public record Site(int x, int y, int z) {
    }

    private final List<Site> glacierSites = new ArrayList<>();
    private final List<Site> polarBearSites = new ArrayList<>();

    public void addGlacier(int x, int y, int z) {
        glacierSites.add(new Site(x, y, z));
    }

    public void addPolarBear(int x, int y, int z) {
        polarBearSites.add(new Site(x, y, z));
    }

    public List<Site> getGlacierSites() {
        return glacierSites;
    }

    public List<Site> getPolarBearSites() {
        return polarBearSites;
    }

    public void clear() {
        glacierSites.clear();
        polarBearSites.clear();
    }
}
