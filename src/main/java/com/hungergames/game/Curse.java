package com.hungergames.game;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Custom curses branded onto players who spill blood in or into a church.
 * Deliberately no potion effects: every curse is built from attributes,
 * hunger manipulation, mob targeting, or scheduled strikes so it reads as
 * something otherworldly rather than a buff/debuff effect.
 */
public enum Curse {

    // Tier I - minor marks

    EYE_OF_JUDGMENT(1, "Eye of Judgment",
            "Heaven's gaze marks you; all can see your sin.",
            Tier.MINOR) {
        @Override
        public void apply(Player player) {
            player.setGlowing(true);
        }

        @Override
        public void remove(Player player) {
            player.setGlowing(false);
        }
    },

    CURSED_HUNGER(2, "Cursed Hunger",
            "Your body wastes; bread will never fill you.",
            Tier.MINOR) {
        private static final int DRAIN = 3;

        @Override
        public void onSecond(Player player, Game game, Consumer<Runnable> scheduler) {
            scheduler.accept(() -> {
                if (player.getGameMode() == GameMode.SPECTATOR) {
                    return;
                }
                int food = Math.max(0, player.getFoodLevel() - 1);
                player.setFoodLevel(food);
                float saturation = Math.max(0f, player.getSaturation() - DRAIN);
                player.setSaturation(saturation);
            });
        }
    },

    GLASS_BONES(3, "Glass Bones",
            "Your frame is brittle; every blow cuts deeper.",
            Tier.MINOR),

    // Tier II - moderate afflictions

    IRON_HEART(4, "Iron Heart",
            "Your heart rusts; two hearts are lost forever.",
            Tier.MODERATE) {
        private static final UUID MODIFIER_UUID =
                UUID.nameUUIDFromBytes("hg_curse_iron_heart".getBytes());

        @Override
        public void apply(Player player) {
            AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
            if (health == null) {
                return;
            }
            removeModifier(health, MODIFIER_UUID);
            health.addModifier(new AttributeModifier(MODIFIER_UUID, "hg_curse_iron_heart",
                    -8.0, AttributeModifier.Operation.ADD_NUMBER));
            if (player.getHealth() > player.getMaxHealth()) {
                player.setHealth(player.getMaxHealth());
            }
        }

        @Override
        public void remove(Player player) {
            AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                removeModifier(health, MODIFIER_UUID);
            }
        }
    },

    BLOOD_SCENT(5, "Blood Scent",
            "The beasts of the field know your name.",
            Tier.MODERATE) {
        private static final double RANGE_SQ = 16 * 16;

        @Override
        public void onSecond(Player player, Game game, Consumer<Runnable> scheduler) {
            scheduler.accept(() -> {
                if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
                    return;
                }
                Location loc = player.getLocation();
                for (var entity : loc.getWorld().getNearbyEntities(loc, 16, 8, 16)) {
                    if (!(entity instanceof Mob mob)
                            || !(mob.getTarget() instanceof Player currentTarget)
                            || currentTarget.getUniqueId().equals(player.getUniqueId())) {
                        continue;
                    }
                    if (mob.getLocation().distanceSquared(loc) > RANGE_SQ || !mob.isValid()) {
                        continue;
                    }
                    mob.setTarget(player);
                }
            });
        }
    },

    SHAKING_HANDS(6, "Shaking Hands",
            "Your strikes tremble and lose their force.",
            Tier.MODERATE),

    // Tier III - severe damnations

    STORMTOUCHED(7, "Stormtouched",
            "The sky remembers your face and seeks you out.",
            Tier.SEVERE) {
        private static final long INTERVAL_MS = 12_000;
        private static final double DAMAGE = 2.0;
        private final Map<UUID, Long> lastStrikeByPlayer = new HashMap<>();

        @Override
        public void apply(Player player) {
            lastStrikeByPlayer.put(player.getUniqueId(), System.currentTimeMillis());
        }

        @Override
        public void onSecond(Player player, Game game, Consumer<Runnable> scheduler) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            PlayerSession session = game.getSession(player.getUniqueId());
            if (session != null && session.isInRespawnGrace()) {
                return;
            }
            UUID id = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastStrikeByPlayer.get(id);
            if (last != null && now - last < INTERVAL_MS) {
                return;
            }
            lastStrikeByPlayer.put(id, now);
            scheduler.accept(() -> {
                if (!player.isOnline() || player.isDead()) {
                    return;
                }
                PlayerSession current = game.getSession(player.getUniqueId());
                if (current == null || !current.hasCurse(STORMTOUCHED)
                        || current.isInRespawnGrace()) {
                    return;
                }
                if (game.getState() != GameState.ACTIVE && game.getState() != GameState.GRACE) {
                    return;
                }
                Location loc = player.getLocation();
                loc.add((Math.random() - 0.5) * 4, 0, (Math.random() - 0.5) * 4);
                loc.getWorld().strikeLightningEffect(loc);
                player.damage(DAMAGE);
            });
        }

        @Override
        public void remove(Player player) {
            lastStrikeByPlayer.remove(player.getUniqueId());
        }
    },

    SINNERS_BURDEN(8, "Sinner's Burden",
            "Your feet drag as if wading through sin itself.",
            Tier.SEVERE) {
        private static final UUID MODIFIER_UUID =
                UUID.nameUUIDFromBytes("hg_curse_sinners_burden".getBytes());

        @Override
        public void apply(Player player) {
            AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed == null) {
                return;
            }
            removeModifier(speed, MODIFIER_UUID);
            speed.addModifier(new AttributeModifier(MODIFIER_UUID, "hg_curse_sinners_burden",
                    -0.30, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }

        @Override
        public void remove(Player player) {
            AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                removeModifier(speed, MODIFIER_UUID);
            }
        }
    },

    // Tier IV - primal transformation

    BEAST_CAT(10, "Church Cat",
            "You are reduced to a cat; claws only, hands forever lost.",
            Tier.PRIMAL) {
        private static final UUID MODIFIER_UUID =
                UUID.nameUUIDFromBytes("hg_curse_beast_form".getBytes());

        @Override
        public EntityType beastType() {
            return EntityType.CAT;
        }

        @Override
        public void apply(Player player) {
            AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                removeModifier(health, MODIFIER_UUID);
                health.addModifier(new AttributeModifier(MODIFIER_UUID, "hg_curse_beast_cat",
                        -12.0, AttributeModifier.Operation.ADD_NUMBER));
            }
            AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                removeModifier(speed, MODIFIER_UUID);
                speed.addModifier(new AttributeModifier(MODIFIER_UUID, "hg_curse_beast_cat",
                        0.25, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }

        @Override
        public void remove(Player player) {
            AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                removeModifier(health, MODIFIER_UUID);
            }
            AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                removeModifier(speed, MODIFIER_UUID);
            }
        }
    },

    BEAST_DOG(11, "Hound of Wrath",
            "You are remade a hound; fangs and fury, nothing more.",
            Tier.PRIMAL) {
        private static final UUID MODIFIER_UUID =
                UUID.nameUUIDFromBytes("hg_curse_beast_form".getBytes());

        @Override
        public EntityType beastType() {
            return EntityType.WOLF;
        }

        @Override
        public void apply(Player player) {
            AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                removeModifier(health, MODIFIER_UUID);
                health.addModifier(new AttributeModifier(MODIFIER_UUID, "hg_curse_beast_dog",
                        -6.0, AttributeModifier.Operation.ADD_NUMBER));
            }
        }

        @Override
        public void remove(Player player) {
            AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                removeModifier(health, MODIFIER_UUID);
            }
        }
    },

    BEAST_POLAR_BEAR(12, "White Bruin",
            "You are given to the wild as a polar bear; mighty but mindless.",
            Tier.PRIMAL) {
        @Override
        public EntityType beastType() {
            return EntityType.POLAR_BEAR;
        }
    };

    public enum Tier { MINOR, MODERATE, SEVERE, PRIMAL }

    private static final List<Curse> MINOR = List.of(EYE_OF_JUDGMENT, CURSED_HUNGER, GLASS_BONES);
    private static final List<Curse> MODERATE = List.of(IRON_HEART, BLOOD_SCENT, SHAKING_HANDS);
    private static final List<Curse> SEVERE = List.of(STORMTOUCHED, SINNERS_BURDEN);
    private static final List<Curse> PRIMAL = List.of(BEAST_CAT, BEAST_DOG, BEAST_POLAR_BEAR);

    private final int id;
    private final String displayName;
    private final String lore;
    private final Tier tier;

    Curse(int id, String displayName, String lore, Tier tier) {
        this.id = id;
        this.displayName = displayName;
        this.lore = lore;
        this.tier = tier;
    }

    public void apply(Player player) {
    }

    /** Called once per second while ACTIVE; schedule world-touching work via scheduler. */
    public void onSecond(Player player, Game game, Consumer<Runnable> scheduler) {
    }

    public void remove(Player player) {
    }

    public boolean hasPerSecondEffect() {
        return tier == Tier.MINOR ? this == CURSED_HUNGER : this == BLOOD_SCENT || this == STORMTOUCHED;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLore() {
        return lore;
    }

    /** First church offense is tier 1, second is tier 2, up to primal at 4. */
    public static Curse randomForOffense(int offense, Random random) {
        List<Curse> pool = poolForOffense(offense);
        return pool.get(random.nextInt(pool.size()));
    }

    private static List<Curse> poolForOffense(int offense) {
        int tier = Math.max(1, Math.min(offense, 4));
        return switch (tier) {
            case 1 -> MINOR;
            case 2 -> MODERATE;
            case 3 -> SEVERE;
            default -> PRIMAL;
        };
    }

    public int getTierNumber() {
        return tier.ordinal() + 1;
    }

    public String getTierRoman() {
        return switch (tier) {
            case MINOR -> "I";
            case MODERATE -> "II";
            case SEVERE -> "III";
            case PRIMAL -> "IV";
        };
    }

    /** Entity type for PRIMAL beast forms; null for non-transformation curses. */
    public EntityType beastType() {
        return null;
    }

    public boolean isBeastForm() {
        return tier == Tier.PRIMAL;
    }

    public double beastWalkSpeed() {
        if (this == BEAST_CAT) {
            return 0.32;
        }
        if (this == BEAST_DOG) {
            return 0.28;
        }
        return 0.22;
    }

    public double beastClawDamage() {
        if (this == BEAST_POLAR_BEAR) {
            return 6.0;
        }
        if (this == BEAST_DOG) {
            return 4.0;
        }
        return 2.0;
    }

    public double beastMeleeRange() {
        if (this == BEAST_POLAR_BEAR) {
            return 2.6;
        }
        if (this == BEAST_DOG) {
            return 2.1;
        }
        return 1.7;
    }

    static void removeModifier(AttributeInstance attribute, UUID uuid) {
        if (attribute == null || uuid == null) {
            return;
        }
        for (AttributeModifier modifier : List.copyOf(attribute.getModifiers())) {
            if (uuid.equals(modifier.getUniqueId())) {
                attribute.removeModifier(modifier);
            }
        }
    }

    static void removeCurseModifiers(AttributeInstance attribute) {
        if (attribute == null) {
            return;
        }
        for (AttributeModifier modifier : List.copyOf(attribute.getModifiers())) {
            String name = modifier.getName();
            if (name != null && name.contains("hg_curse")) {
                attribute.removeModifier(modifier);
            }
        }
    }

    public int getId() {
        return id;
    }

    public Tier getTier() {
        return tier;
    }
}
