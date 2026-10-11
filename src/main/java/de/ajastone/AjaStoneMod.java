package de.ajastone;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fuegt den "Roten Stein von Aja" hinzu. Wer als Vampir (JCraft: Eyes of Ender)
 * die Steinmaske traegt UND den Roten Stein von Aja im Inventar hat,
 * verbrennt nicht mehr in der Sonne.
 */
@Mod("ajastone")
public class AjaStoneMod {
    public static final String MOD_ID = "ajastone";

    // --- Einstellungen ---
    private static final int CHECK_INTERVAL = 5;           // alle X Ticks Voraussetzungen pruefen
    private static final String MASK_NAMESPACE = "jcraft"; // Mod der Steinmaske
    private static final String MASK_NAME_PART = "mask";   // Item-ID der Maske enthaelt dieses Wort
    // ---------------------

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final RegistryObject<Item> RED_STONE_OF_AJA = ITEMS.register("red_stone_of_aja",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    private final Map<UUID, Boolean> protectedPlayers = new HashMap<>();

    public AjaStoneMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(this::addToCreativeTab);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(RED_STONE_OF_AJA);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        UUID id = player.getUUID();
        if (player.tickCount % CHECK_INTERVAL == 0) {
            protectedPlayers.put(id, hasMask(player) && hasStone(player) && isVampire(player));
        }
        if (!Boolean.TRUE.equals(protectedPlayers.get(id))) return;

        if (player.isOnFire() && isInSun(player)) {
            player.clearFire();
        }
    }

    /** Falls JCraft den Sonnenbrand als normalen Brandschaden macht, wird dieser hier verhindert. */
    @SubscribeEvent
    public void onAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!Boolean.TRUE.equals(protectedPlayers.get(player.getUUID()))) return;
        if (event.getSource().is(DamageTypes.ON_FIRE) && isInSun(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        protectedPlayers.remove(event.getEntity().getUUID());
    }

    private static boolean isInSun(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        return level.isDay() && !level.isRainingAt(pos) && level.canSeeSky(pos);
    }

    private static boolean hasMask(ServerPlayer player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty()) return false;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(head.getItem());
        return key != null
                && key.getNamespace().equals(MASK_NAMESPACE)
                && key.getPath().contains(MASK_NAME_PART);
    }

    private static boolean hasStone(ServerPlayer player) {
        Item stone = RED_STONE_OF_AJA.get();
        Inventory inv = player.getInventory();
        for (ItemStack s : inv.items) if (s.is(stone)) return true;
        for (ItemStack s : inv.offhand) if (s.is(stone)) return true;
        return false;
    }

    /** Liest den Vampir-Status ueber die Capability-NBT (wie /data get entity @s ForgeCaps). */
    private static boolean isVampire(ServerPlayer player) {
        CompoundTag tag = player.saveWithoutId(new CompoundTag());
        return tag.getCompound("ForgeCaps").getCompound("jcraft:vampire_capability").getBoolean("Vampire");
    }
}
