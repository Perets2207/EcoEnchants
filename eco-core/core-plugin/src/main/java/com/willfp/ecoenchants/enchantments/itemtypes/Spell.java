package com.willfp.ecoenchants.enchantments.itemtypes;

import com.willfp.eco.util.optional.Prerequisite;
import com.willfp.ecoenchants.display.EnchantmentCache;
import com.willfp.ecoenchants.enchantments.EcoEnchant;
import com.willfp.ecoenchants.enchantments.EcoEnchants;
import com.willfp.ecoenchants.enchantments.meta.EnchantmentType;
import com.willfp.ecoenchants.enchantments.util.EnchantChecks;
import com.willfp.ecoenchants.enchantments.util.SpellActivateEvent;
import com.willfp.ecoenchants.enchantments.util.SpellCancellationInterrupt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.util.NumberConversions;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public abstract class Spell extends EcoEnchant {
    /**
     * The cooldown end times linked to players.
     */
    private final Map<UUID, Long> tracker = new HashMap<>();

    /**
     * Players currently running spells - prevents listener firing twice.
     */
    private final Set<UUID> preventDuplicateList = new HashSet<>();

    /**
     * Items that must be left-clicked to activate spells for.
     */
    private static final List<Material> LEFT_CLICK_ITEMS = Arrays.asList(
            Material.FISHING_ROD,
            Material.BOW
    );

    /**
     * Create a new spell enchantment.
     *
     * @param key           The key name of the enchantment
     * @param prerequisites Optional {@link Prerequisite}s that must be met
     */
    protected Spell(@NotNull final String key,
                    @NotNull final Prerequisite... prerequisites) {
        super(key, EnchantmentType.SPELL, prerequisites);
    }

    /**
     * Get the cooldown time of the spell (in seconds).
     *
     * @return The time, in seconds.
     */
    public int getCooldownTime() {
        return this.getConfig().getInt(EcoEnchants.CONFIG_LOCATION + "cooldown");
    }

    /**
     * Get the sound to be played on activation.
     *
     * @return The sound.
     */
    public final Sound getActivationSound() {
        return Sound.valueOf(this.getConfig().getString(EcoEnchants.CONFIG_LOCATION + "activation-sound").toUpperCase());
    }

    /**
     * Listener called on spell activation.
     *
     * @param event The event to listen for.
     */
    @EventHandler
    public void onUseEventHandler(@NotNull final PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (preventDuplicateList.contains(player.getUniqueId())) {
            return;
        }
        preventDuplicateList.add(player.getUniqueId());
        this.getPlugin().getScheduler().runLater(() -> preventDuplicateList.remove(player.getUniqueId()), 5);

        if (LEFT_CLICK_ITEMS.contains(player.getInventory().getItemInMainHand().getType())) {
            if (!(event.getAction().equals(Action.LEFT_CLICK_AIR) || event.getAction().equals(Action.LEFT_CLICK_BLOCK))) {
                return;
            }
            if (requiresBlockClick() && !event.getAction().equals(Action.LEFT_CLICK_BLOCK)) {
                return;
            }
        } else {
            if (!(event.getAction().equals(Action.RIGHT_CLICK_AIR) || event.getAction().equals(Action.RIGHT_CLICK_BLOCK))) {
                return;
            }
            if (requiresBlockClick() && !event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
                return;
            }
        }

        if (!EnchantChecks.mainhand(player, this)) {
            return;
        }

        int level = EnchantChecks.getMainhandLevel(player, this);
        if (this.getDisabledWorlds().contains(player.getWorld())) {
            return;
        }

        int cooldown = getCooldown(this, player);

        if (event.getClickedBlock() != null) {
            if (event.getClickedBlock().getState() instanceof Container
                    || event.getClickedBlock().getState() instanceof BlockInventoryHolder
                    || event.getClickedBlock().getType() == Material.CRAFTING_TABLE
                    || event.getClickedBlock().getType() == Material.GRINDSTONE
                    || event.getClickedBlock().getType() == Material.ENCHANTING_TABLE
                    || event.getClickedBlock().getType() == Material.ANVIL
                    || event.getClickedBlock().getType() == Material.FURNACE) {
                return;
            }

            if (Prerequisite.MINIMUM_1_16.isMet()) {
                if (event.getClickedBlock().getType() == Material.SMITHING_TABLE) {
                    return;
                }
            }
        }

        if (cooldown > 0) {
            String message = this.getPlugin().getLangYml().getMessage("on-cooldown").replace("%seconds%", String.valueOf(cooldown)).replace("%name%", EnchantmentCache.getEntry(this).getRawName());
            player.sendMessage(message);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1, 0.5f);
        } else {
            tracker.remove(player.getUniqueId());
            tracker.put(player.getUniqueId(), System.currentTimeMillis() + (long) ((this.getCooldownTime() * 1000L) * Spell.getCooldownMultiplier(player)));

            SpellActivateEvent spellActivateEvent = new SpellActivateEvent(player, this);
            Bukkit.getPluginManager().callEvent(spellActivateEvent);

            if (!spellActivateEvent.isCancelled()) {
                try {
                    onUse(player, level, event);
                } catch (SpellCancellationInterrupt ignored) {
                    return;
                }

                String message = this.getPlugin().getLangYml().getMessage("used-spell").replace("%name%", EnchantmentCache.getEntry(this).getRawName());
                player.sendMessage(message);
                player.playSound(player.getLocation(), this.getActivationSound(), SoundCategory.PLAYERS, 1, 1);
            }
        }
    }

    /**
     * Get if the spell requires a block to be clicked to trigger the spell.
     *
     * @return If the spell requires a block to be clicked.
     */
    protected boolean requiresBlockClick() {
        return false;
    }

    /**
     * Actual spell-specific implementations; the functionality.
     *
     * @param player The player who triggered the spell.
     * @param level  The level of the spell on the item.
     * @param event  The event that activated the spell.
     */
    public abstract void onUse(@NotNull Player player,
                               int level,
                               @NotNull PlayerInteractEvent event) throws SpellCancellationInterrupt;

    /**
     * Utility method to get a player's cooldown time of a specific spell.
     *
     * @param spell  The spell to query.
     * @param player The player to query.
     * @return The time left in seconds before next use.
     */
    public static int getCooldown(@NotNull final Spell spell,
                                  @NotNull final Player player) {
        if (!spell.tracker.containsKey(player.getUniqueId())) {
            return 0;
        }

        long msLeft = spell.tracker.get(player.getUniqueId()) - System.currentTimeMillis();

        long secondsLeft = (long) Math.ceil((double) msLeft / 1000);

        return NumberConversions.toInt(secondsLeft);
    }

    /**
     * Get a multiplier for a spell cooldown.
     * <p>
     * Used for perks - this should be reworked as it has hardcoded permission references.
     *
     * @param player The player to query.
     * @return The multipiler.
     */
    public static double getCooldownMultiplier(@NotNull final Player player) {
        if (player.hasPermission("ecoenchants.cooldowntime.quarter")) {
            return 0.25;
        }

        if (player.hasPermission("ecoenchants.cooldowntime.third")) {
            return 0.33;
        }

        if (player.hasPermission("ecoenchants.cooldowntime.half")) {
            return 0.5;
        }

        if (player.hasPermission("ecoenchants.cooldowntime.75")) {
            return 0.75;
        }

        return 1;
    }
}
