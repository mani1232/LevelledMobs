package io.github.lokka30.levelledmobs;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.lokka30.levelledmobs.enums.ModalList;
import io.github.lokka30.levelledmobs.listeners.CreatureSpawnListener;
import io.github.lokka30.levelledmobs.utils.Utils;
import me.lokka30.microlib.MicroUtils;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.regex.Pattern;

public class LevelManager {

    private final LevelledMobs instance;

    public LevelManager(LevelledMobs instance) {
        this.instance = instance;

        levelKey = new NamespacedKey(instance, "level");
        isLevelledKey = new NamespacedKey(instance, "isLevelled");
    }

    public final NamespacedKey levelKey; // This stores the mob's level.
    public final NamespacedKey isLevelledKey; //This is stored on levelled mobs to tell plugins that it is a levelled mob.

    public final EnumSet<EntityType> forcedTypes = EnumSet.of(EntityType.GHAST, EntityType.MAGMA_CUBE, EntityType.HOGLIN, EntityType.SHULKER, EntityType.PHANTOM, EntityType.ENDER_DRAGON);

    public final static int maxCreeperBlastRadius = 100;
    public final Pattern slimeRegex = Pattern.compile("Level.*?(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    public CreatureSpawnListener creatureSpawnListener;

    public boolean isLevellable(final EntityType entityType) {
        // Don't level these
        if (entityType == EntityType.PLAYER || entityType == EntityType.UNKNOWN) return false;

        // Check if the entity is overriden. If so, force it to be levelled.
        if (instance.settingsCfg.getStringList("overriden-entities").contains(entityType.toString())) return true;

        // Check if the entity is blacklisted. If not, continue.
        if(!ModalList.isEnabledInList(instance.settingsCfg, "allowed-entities-list", entityType.toString())) return false;

        // These entities don't implement Monster or Boss and thus must be forced to return true
        if (forcedTypes.contains(entityType)) {
            return true;
        }

        // Grab the Entity class, which is used to check for certain assignments
        Class<? extends Entity> entityClass = entityType.getEntityClass();
        if (entityClass == null) return false;

        return Monster.class.isAssignableFrom(entityClass)
                || Boss.class.isAssignableFrom(entityClass)
                || instance.settingsCfg.getBoolean("level-passive");
    }

    //Checks if an entity can be levelled.
    public boolean isLevellable(final LivingEntity entity) {

        //Players shouldn't be levelled! Keep this at the top to ensure they don't return true
        if (entity.getType() == EntityType.PLAYER || entity.getType() == EntityType.UNKNOWN || entity.hasMetadata("NPC")) {
            return false;
        }

        // Check WorldGuard flag.
        if (instance.hasWorldGuardInstalled && !instance.worldGuardManager.regionAllowsLevelling(entity)) return false;

        // Check for overrides
        if(instance.settingsCfg.getStringList("overriden-entities").contains(entity.getType().toString())) return true;

        //Check allowed entities for normal entity types
        if(!ModalList.isEnabledInList(instance.settingsCfg, "allowed-entities-list", entity.getType().toString())) return false;

        // Specific allwoed entities check for BABY_ZOMBIE
        if (entity instanceof Zombie) {
            final Zombie zombie = (Zombie) entity;
            if (!zombie.isAdult()) {
                if (!ModalList.isEnabledInList(instance.settingsCfg, "allowed-entities-list", "BABY_ZOMBIE"))
                    return false;
            }
        }

        return isLevellable(entity.getType());
    }

    //Updates the livingEntity's nametag after a 1 tick delay. Without the delay, it would
    //display the livingEntity's previous health rather than their new health.
    //Used on EntityDamageEvent and EntityRegainHealthEvent.
    public void updateNametagWithDelay(LivingEntity livingEntity) {
        updateNametagWithDelay(livingEntity, getNametag(livingEntity));
    }

    public void updateNametagWithDelay(LivingEntity livingEntity, String nametag) {
        new BukkitRunnable() {
            public void run() {
                if (livingEntity == null) return; // may have died 1 tick later.

                updateNametag(livingEntity, nametag);
            }
        }.runTaskLater(instance, 1L);
    }

    //Calculates the drops when a levellable creature dies.
    public void setLevelledDrops(final LivingEntity ent, List<ItemStack> drops) {

        if (isLevellable(ent)) {
            //If mob is levellable, but wasn't levelled, return.
            Integer level = ent.getPersistentDataContainer().get(instance.levelManager.levelKey, PersistentDataType.INTEGER);
            if (level == null)
                return;

            //Read settings for drops.
            double dropMultiplier = instance.settingsCfg.getDouble("fine-tuning.additions.custom.item-drop");
            int finalMultiplier = 1;

            //If multiplier * level gurantees an extra drop set 'finalMultiplier' to the amount of safe multiples.
            dropMultiplier *= level;
            finalMultiplier += (int) dropMultiplier;
            dropMultiplier -= (int) dropMultiplier;

            //Calculate if the remaining extra drop chance triggers.
            double random = new Random().nextDouble();
            if (random < dropMultiplier) {
                finalMultiplier++;
            }

            //Remove the hand item from the mob's drops so it doesn't get multiplied
            ItemStack helmet = null;
            ItemStack chestplate = null;
            ItemStack leggings = null;
            ItemStack boots = null;
            ItemStack mainHand = null;
            ItemStack offHand = null;
            if (ent.getEquipment() != null) {
                helmet = ent.getEquipment().getHelmet();
                chestplate = ent.getEquipment().getChestplate();
                leggings = ent.getEquipment().getLeggings();
                boots = ent.getEquipment().getBoots();
                mainHand = ent.getEquipment().getItemInMainHand();
                offHand = ent.getEquipment().getItemInOffHand();
            }

            //Edit the ItemStacks to drop the calculated multiple items.
            for (int i = 0; i < drops.size(); i++) {
                ItemStack itemStack = drops.get(i);

                int amount = itemStack.getAmount() * finalMultiplier;

                //Don't let the drops go over the max stack size.
                int maxStackSize = itemStack.getMaxStackSize();
                if (amount > maxStackSize) {
                    amount = maxStackSize;
                }

                //Don't let the plugin multiply items which match their equipment. stops bows and that from multiplying
                if (helmet != null && itemStack.isSimilar(helmet)) {
                    amount = helmet.getAmount();
                }
                if (chestplate != null && itemStack.isSimilar(chestplate)) {
                    amount = chestplate.getAmount();
                }
                if (leggings != null && itemStack.isSimilar(leggings)) {
                    amount = leggings.getAmount();
                }
                if (boots != null && itemStack.isSimilar(boots)) {
                    amount = boots.getAmount();
                }
                if (mainHand != null && itemStack.isSimilar(mainHand)) {
                    amount = mainHand.getAmount();
                }
                if (offHand != null && itemStack.isSimilar(offHand)) {
                    amount = offHand.getAmount();
                }

                itemStack.setAmount(amount);
                drops.set(i, itemStack);
            }
        }
    }

    //Calculates the XP dropped when a levellable creature dies.
    public int setLevelledXP(final LivingEntity ent, int xp) {
        if (ent.getPersistentDataContainer().has(isLevelledKey, PersistentDataType.STRING)) {
            double xpPerLevel = instance.settingsCfg.getDouble("fine-tuning.additions.custom.xp-drop");
            int level = Objects.requireNonNull(ent.getPersistentDataContainer().get(instance.levelManager.levelKey, PersistentDataType.INTEGER));

            xp = (int) (xp + (xpPerLevel * level));
        }
        return xp;
    }

    // When the persistent data container levelled key has been set on the entity already (i.e. when they are damaged)
    public String getNametag(LivingEntity livingEntity) {
        return getNametag(livingEntity, Objects.requireNonNull(
                livingEntity.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER)));
    }

    // When the persistent data container levelled key has not been set on the entity yet (i.e. for use in CreatureSpawnListener)
    public String getNametag(LivingEntity livingEntity, int level) {
        String entityName = livingEntity.getCustomName() == null ? instance.configUtils.getEntityName(livingEntity.getType()) : livingEntity.getCustomName();

        AttributeInstance maxHealth = livingEntity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        String health = maxHealth == null ? "?" : Utils.round(maxHealth.getBaseValue()) + "";

        String nametag = instance.settingsCfg.getString("creature-nametag");
        nametag = Utils.replaceEx(nametag, "%level%", level + "");
        nametag = Utils.replaceEx(nametag, "%name%", entityName);
        nametag = Utils.replaceEx(nametag, "%health%", Utils.round(livingEntity.getHealth()) + "");
        nametag = Utils.replaceEx(nametag, "%max_health%", health);
        nametag = Utils.replaceEx(nametag, "%heart_symbol%", "❤");
        assert nametag != null;
        nametag = MicroUtils.colorize(nametag);

        return nametag;
    }

    public void updateNametag(LivingEntity entity, String nametag) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            updateNametag(entity, nametag, onlinePlayer);
        }
    }

    /*
     * Credit
     * - Thread: https://www.spigotmc.org/threads/changing-an-entitys-nametag-with-packets.482855/
     *
     * - Users:
     *   - @CoolBoy (https://www.spigotmc.org/members/CoolBoy.102500/)
     *   - @Esophose (https://www.spigotmc.org/members/esophose.34168/)
     *   - @7smile7 (https://www.spigotmc.org/members/7smile7.43809/)
     */
    public void updateNametag(LivingEntity entity, String nametag, Player player) {

        WrappedDataWatcher dataWatcher = WrappedDataWatcher.getEntityWatcher(entity).deepClone();
        WrappedDataWatcher.Serializer chatSerializer = WrappedDataWatcher.Registry.getChatComponentSerializer(true);
        WrappedDataWatcher.WrappedDataWatcherObject watcherObject = new WrappedDataWatcher.WrappedDataWatcherObject(2, chatSerializer);
        Optional<Object> optional = Optional.of(WrappedChatComponent.fromChatMessage(nametag)[0].getHandle());
        dataWatcher.setObject(watcherObject, optional);
        dataWatcher.setObject(3, instance.settingsCfg.getBoolean("creature-nametag-always-visible"));

        PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getWatchableCollectionModifier().write(0, dataWatcher.getWatchableObjects());
        packet.getIntegers().write(0, entity.getEntityId());

        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (InvocationTargetException ex) {
            Utils.logger.error("Unable to update nametag packet for player &b" + player.getName() + "&7! Stack trace:");
            ex.printStackTrace();
        }
    }
}