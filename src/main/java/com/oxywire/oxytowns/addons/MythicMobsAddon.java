package com.oxywire.oxytowns.addons;

import com.oxywire.oxytowns.OxyTownsPlugin;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.logging.Level;

public final class MythicMobsAddon implements Listener {

    public static boolean hookActive = false;

    public MythicMobsAddon() {
        OxyTownsPlugin.get().getLogger().log(Level.INFO, "MythicMobs hooked! Begone Nosfartutu!!");
        hookActive = true;
        OxyTownsPlugin.get().getServer().getPluginManager().registerEvents(this, OxyTownsPlugin.get());
    }

    @EventHandler
    public void onMythicMobSpawn(final MythicMobSpawnEvent event) {
        final var town = OxyTownsPlugin.get().getTownCache().getTownByLocation(event.getLocation());
        if (town == null) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onMythicMobMove(final EntityMoveEvent event) {
        final var from = event.getFrom();
        final var to = event.getTo();
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4) && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) return;

        final var town = OxyTownsPlugin.get().getTownCache().getTownByLocation(to);
        if (town == null) return;

        removeIfMythicMob(event.getEntity());
    }

    public static void removeIfMythicMob(final Entity entity) {
        if (!hookActive) return;

        MythicBukkit.inst().getMobManager().getActiveMob(entity.getUniqueId()).ifPresent(activeMob -> {
            if (!"friendly".equalsIgnoreCase(activeMob.getFaction())) {
                entity.remove();
            }
        });
    }
}
