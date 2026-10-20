package dev.sellworth;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Adds "Worth: $X" (whole stack, damage-adjusted) to the tooltip of every sellable item.
 * Done at packet level, so the real items on the server are never modified.
 */
public final class WorthLoreListener {

    private static final String PREFIX = "Worth: $";
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private WorthLoreListener() { }

    public static void register(JavaPlugin plugin, PriceManager prices) {
        // Look the packet types up by name so this works across ProtocolLib / Minecraft versions.
        List<PacketType> types = new ArrayList<>();
        for (String name : List.of("SET_SLOT", "WINDOW_ITEMS", "SET_CURSOR_ITEM", "SET_PLAYER_INVENTORY")) {
            try {
                Object t = PacketType.Play.Server.class.getField(name).get(null);
                if (t instanceof PacketType pt && pt.isSupported()) types.add(pt);
            } catch (Throwable ignored) {
                // packet doesn't exist on this version
            }
        }
        if (types.isEmpty()) {
            plugin.getLogger().warning("Hover worth: ProtocolLib has no supported inventory packets for this server version.");
            return;
        }

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.HIGH, types.toArray(new PacketType[0])) {
            @Override
            public void onPacketSending(PacketEvent event) {
                try {
                    PacketContainer packet = event.getPacket();
                    if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                        List<ItemStack> items = packet.getItemListModifier().read(0);
                        List<ItemStack> out = new ArrayList<>(items.size());
                        for (ItemStack it : items) out.add(decorate(prices, it));
                        packet.getItemListModifier().write(0, out);

                        if (packet.getItemModifier().size() > 0) { // carried (cursor) item
                            packet.getItemModifier().write(0, decorate(prices, packet.getItemModifier().read(0)));
                        }
                    } else if (packet.getItemModifier().size() > 0) {
                        packet.getItemModifier().write(0, decorate(prices, packet.getItemModifier().read(0)));
                    }
                } catch (Throwable ex) {
                    warnOnce(plugin, "sending " + event.getPacketType(), ex);
                }
            }
        });

        // Creative clients send their (decorated) items back to the server.
        // Strip our line again so it never gets saved into real items.
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.LOW, PacketType.Play.Client.SET_CREATIVE_SLOT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                try {
                    PacketContainer packet = event.getPacket();
                    if (packet.getItemModifier().size() > 0) {
                        ItemStack it = packet.getItemModifier().read(0);
                        ItemStack cleaned = strip(it);
                        if (cleaned != it) packet.getItemModifier().write(0, cleaned);
                    }
                } catch (Throwable ex) {
                    warnOnce(plugin, "receiving SET_CREATIVE_SLOT", ex);
                }
            }
        });

        plugin.getLogger().info("Hover worth enabled (packets: " + types + ").");
    }

    private static void warnOnce(JavaPlugin plugin, String what, Throwable ex) {
        if (WARNED.compareAndSet(false, true)) {
            plugin.getLogger().log(Level.WARNING,
                    "Hover worth failed while " + what + ". Make sure ProtocolLib supports your Minecraft version.", ex);
        }
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static ItemStack decorate(PriceManager prices, ItemStack item) {
        if (item == null || item.getType().isAir()) return item;

        double total = prices.valueOf(item);
        if (total <= 0) return item;

        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return item;
        if (meta.getPersistentDataContainer().has(prices.noLoreKey)) return item; // GUI items

        List<Component> existing = meta.lore();
        List<Component> lore = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        lore.removeIf(c -> plain(c).startsWith(PREFIX));
        lore.add(PriceManager.worthLine(total));
        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    /** Removes our worth line; returns the same instance if there was none. */
    private static ItemStack strip(ItemStack item) {
        if (item == null || item.getType().isAir()) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) return item;

        List<Component> filtered = new ArrayList<>(lore);
        filtered.removeIf(c -> plain(c).startsWith(PREFIX));
        if (filtered.size() == lore.size()) return item;

        ItemStack copy = item.clone();
        ItemMeta m = copy.getItemMeta();
        m.lore(filtered.isEmpty() ? null : filtered);
        copy.setItemMeta(m);
        return copy;
    }
}
