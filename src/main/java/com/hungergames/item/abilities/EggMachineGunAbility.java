package com.hungergames.item.abilities;

import com.hungergames.HungerGamesPlugin;
import com.hungergames.item.AbilityType;
import com.hungergames.item.AbilityUseGuard;
import com.hungergames.item.CustomItemAbility;
import com.hungergames.item.CustomItemDefinition;
import com.hungergames.item.CustomItemRegistry;
import com.hungergames.util.GameCombatUtil;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EggMachineGunAbility implements CustomItemAbility {

    public static final String PROJECTILE_TYPE = "egg_machine_gun";

    private final HungerGamesPlugin plugin;
    private final AbilityUseGuard useGuard;
    private final Map<UUID, FiringSession> firingSessions = new HashMap<>();

    private static final class FiringSession {
        private final BukkitTask task;
        private final CustomItemDefinition definition;

        private FiringSession(BukkitTask task, CustomItemDefinition definition) {
            this.task = task;
            this.definition = definition;
        }
    }

    public EggMachineGunAbility(HungerGamesPlugin plugin, AbilityUseGuard useGuard) {
        this.plugin = plugin;
        this.useGuard = useGuard;
    }

    @Override
    public AbilityType type() {
        return AbilityType.EGG_MACHINE_GUN;
    }

    @Override
    public void onActivate(Player player, CustomItemDefinition definition, PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (isFiring(player)) {
            stopFiring(player);
            event.setCancelled(true);
            return;
        }

        if (useGuard.isOnCooldown(player, definition)) {
            plugin.getCustomItemRegistry().getCooldownManager().sendCooldownMessage(plugin, player,
                    (int) plugin.getCustomItemRegistry().getCooldownManager()
                            .getRemainingSeconds(player, definition.getId()));
            event.setCancelled(true);
            return;
        }

        if (!useGuard.tryStartWithoutCooldown(player, item, definition, event)) {
            return;
        }

        startFiring(player, item, definition, event.getHand());
        player.sendMessage(plugin.formatMessage("&eEgg Machine Gun firing! Right-click again to stop."));
        event.setCancelled(true);
    }

    private void startFiring(Player player, ItemStack item, CustomItemDefinition definition, EquipmentSlot hand) {
        int fireRate = definition.getInt("fireRate", 10);
        long periodTicks = Math.max(1L, 20L / fireRate);
        double damage = definition.getDouble("damage", 1.0);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !GameCombatUtil.isActiveCombatant(plugin, player)) {
                stopFiring(player);
                return;
            }

            ItemStack held = hand == EquipmentSlot.OFF_HAND
                    ? player.getInventory().getItemInOffHand()
                    : player.getInventory().getItemInMainHand();
            if (!plugin.getCustomItemRegistry().getFromItem(held)
                    .map(def -> def.getId().equals(definition.getId()))
                    .orElse(false)) {
                stopFiring(player);
                return;
            }

            if (!useGuard.hasCharges(held, definition)) {
                stopFiring(player);
                return;
            }

            Egg egg = player.launchProjectile(Egg.class);
            egg.setShooter(player);
            Vector direction = player.getLocation().getDirection().normalize().multiply(1.5);
            egg.setVelocity(direction);
            egg.getPersistentDataContainer().set(
                    CustomItemRegistry.PROJECTILE_TYPE_KEY, PersistentDataType.STRING, PROJECTILE_TYPE);
            egg.getPersistentDataContainer().set(
                    CustomItemRegistry.PROJECTILE_OWNER_KEY, PersistentDataType.STRING, player.getUniqueId().toString());
            egg.getPersistentDataContainer().set(
                    CustomItemRegistry.PROJECTILE_DAMAGE_KEY, PersistentDataType.DOUBLE, damage);

            if (!useGuard.consumeCharge(player, held, definition, hand)) {
                stopFiring(player);
            }
        }, 0L, periodTicks);

        firingSessions.put(player.getUniqueId(), new FiringSession(task, definition));
    }

    public void stopFiring(Player player) {
        FiringSession session = firingSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.task.cancel();
        useGuard.applyCooldown(player, session.definition);
    }

    public boolean isFiring(Player player) {
        return firingSessions.containsKey(player.getUniqueId());
    }

    public void handleProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Egg egg)) {
            return;
        }
        String type = egg.getPersistentDataContainer()
                .get(CustomItemRegistry.PROJECTILE_TYPE_KEY, PersistentDataType.STRING);
        if (!PROJECTILE_TYPE.equals(type)) {
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            String ownerId = egg.getPersistentDataContainer()
                    .get(CustomItemRegistry.PROJECTILE_OWNER_KEY, PersistentDataType.STRING);
            if (ownerId == null) {
                event.setCancelled(true);
                return;
            }
            Player shooter = plugin.getServer().getPlayer(java.util.UUID.fromString(ownerId));
            if (shooter == null || !GameCombatUtil.areSameGame(plugin, shooter, victim)) {
                event.setCancelled(true);
                return;
            }
        }
        Double damage = egg.getPersistentDataContainer()
                .get(CustomItemRegistry.PROJECTILE_DAMAGE_KEY, PersistentDataType.DOUBLE);
        if (damage != null) {
            event.setDamage(damage);
        }
    }

    @Override
    public void applyPassive(Player player, CustomItemDefinition definition) {
    }

    @Override
    public void removePassive(Player player, CustomItemDefinition definition) {
        stopFiring(player);
    }
}
