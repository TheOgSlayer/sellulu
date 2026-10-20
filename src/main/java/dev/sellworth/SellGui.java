package dev.sellworth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class SellGui implements Listener {

    /** Bottom-right corner of a double chest. */
    public static final int SELL_SLOT = 53;

    public static class Holder implements InventoryHolder {
        private Inventory inv;
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private final PriceManager prices;
    private final Economy economy;

    public SellGui(PriceManager prices, Economy economy) {
        this.prices = prices;
        this.economy = economy;
    }

    public void open(Player p) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("ᴘʟᴀᴄᴇ ɪᴛᴇᴍꜱ ʜᴇʀᴇ ᴛᴏ ꜱᴇʟʟ", NamedTextColor.GRAY));
        holder.inv = inv;
        inv.setItem(SELL_SLOT, sellButton());
        p.openInventory(inv);
    }

    private ItemStack sellButton() {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Sell Items", NamedTextColor.GREEN, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Click to sell everything", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("in this menu.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        // never show a worth line on the button itself
        meta.getPersistentDataContainer().set(prices.noLoreKey,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void sell(Player p, Inventory inv) {
        double total = 0;
        for (int slot = 0; slot < SELL_SLOT; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir()) continue;
            double value = prices.valueOf(it); // damaged items are worth less
            if (value <= 0) continue; // unsellable items stay in the GUI
            total += value;
            inv.setItem(slot, null);
        }
        total = PriceManager.round(total);

        if (total <= 0) {
            p.sendMessage(Component.text("Nothing in the menu can be sold.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        economy.depositPlayer(p, total);

        // green "+$" followed by the amount in white
        Component msg = Component.text("+$", NamedTextColor.GREEN)
                .append(Component.text(PriceManager.format(total), NamedTextColor.WHITE));
        p.sendMessage(msg);
        p.sendActionBar(msg);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // double-click "collect to cursor" could pull the button out
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        if (e.getClickedInventory() == top && e.getSlot() == SELL_SLOT) {
            e.setCancelled(true);
            sell(p, top);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        if (e.getRawSlots().contains(SELL_SLOT)) e.setCancelled(true);
    }

    /** Give back everything that wasn't sold. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof Holder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;

        for (int slot = 0; slot < SELL_SLOT; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir()) continue;
            p.getInventory().addItem(it).values()
                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        inv.clear();
    }
}
