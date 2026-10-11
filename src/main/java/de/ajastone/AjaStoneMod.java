package de.ajastone;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
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
 * Fuegt den "Roten Stein von Aja" hinzu.
 * Steinmaske (JCraft: Eyes of Ender) tragen + Rechtsklick mit dem Stein
 * = dieser Spieler ist dauerhaft vor Sonnenlicht geschuetzt (Vampire verbrennen nicht mehr).
 * Der Stein wird dabei verbraucht. Der Schutz bleibt auch nach dem Tod erhalten.
 */
@Mod("ajastone")
public class AjaStoneMod {
    public static final String MOD_ID = "ajastone";
    private static final String FLAG = "ajastone_sun_protected";

    // --- Einstellungen ---
    private static final int CHECK_INTERVAL = 5;           // alle X Ticks Vampir-Status pruefen
    private static final String MASK_NAMESPACE = "jcraft"; // Mod der Steinmaske
    private static final String MASK_NAME_PART = "mask";   // Item-ID der Maske enthaelt dieses Wort
    // ---------------------

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final RegistryObject<Item> RED_STONE_OF_AJA = ITEMS.register("red_stone_of_aja",
            () -> new RedStoneOfAjaItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

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

    /** Der Stein: Rechtsklick mit getragener Maske macht den Spieler dauerhaft sonnensicher. */
    public static class RedStoneOfAjaItem extends Item {
        public RedStoneOfAjaItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);

            if (!hasMask(player)) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.translatable("message.ajastone.need_mask"), true);
                }
                return InteractionResultHolder.fail(stack);
            }
            if (level.isClientSide) {
                return InteractionResultHolder.sidedSuccess(stack, true);
            }
            if (player.getPersistentData().getBoolean(FLAG)) {
                player.displayClientMessage(Component.translatable("message.ajastone.already"), true);
                return InteractionResultHolder.fail(stack);
            }

            player.getPersistentData().putBoolean(FLAG, true);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 1.0F);
            player.displayClientMessage(Component.translatable("message.ajastone.success"), true);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        UUID id = player.getUUID();
        if (player.tickCount % CHECK_INTERVAL == 0) {
            protectedPlayers.put(id, player.getPersistentData().getBoolean(FLAG) && isVampire(player));
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

    /** Schutz bleibt nach dem Tod erhalten. */
    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        if (event.getOriginal().getPersistentData().getBoolean(FLAG)) {
            event.getEntity().getPersistentData().putBoolean(FLAG, true);
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

    private static boolean hasMask(Player player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty()) return false;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(head.getItem());
        return key != null
                && key.getNamespace().equals(MASK_NAMESPACE)
                && key.getPath().contains(MASK_NAME_PART);
    }

    /** Liest den Vampir-Status ueber die Capability-NBT (wie /data get entity @s ForgeCaps). */
    private static boolean isVampire(ServerPlayer player) {
        CompoundTag tag = player.saveWithoutId(new CompoundTag());
        return tag.getCompound("ForgeCaps").getCompound("jcraft:vampire_capability").getBoolean("Vampire");
    }
}
