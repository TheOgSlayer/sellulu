package dev.sellworth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class WorthGui implements Listener {

    private static final int PER_PAGE = 45;
    private static final int SLOT_PREV = 45, SLOT_SEARCH = 47, SLOT_CLOSE = 49, SLOT_SORT = 51, SLOT_NEXT = 53;

    public enum Sort {
        ALPHABETICAL("Alphabetical (A-Z)"),
        HIGH_LOW("Highest to Lowest"),
        LOW_HIGH("Lowest to Highest");

        final String label;
        Sort(String label) { this.label = label; }
        Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public static class Holder implements InventoryHolder {
        Inventory inv;
        String search = "";
        Sort sort = Sort.ALPHABETICAL;
        int page = 0;
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    /** A temporary sign used to read the search text from a player. */
    private record SignSession(Block block, BlockData original, Holder state) { }

    private final JavaPlugin plugin;
    private final PriceManager prices;
    private final Map<UUID, SignSession> sessions = new HashMap<>();

    public WorthGui(JavaPlugin plugin, PriceManager prices) {
        this.plugin = plugin;
        this.prices = prices;
    }

    // ------------------------------------------------------------------ open / render

    public void open(Player p, String search) {
        Holder h = new Holder();
        h.search = search == null ? "" : search;
        openHolder(p, h);
    }

    private void openHolder(Player p, Holder state) {
        Holder h = new Holder();
        h.search = state.search;
        h.sort = state.sort;
        h.page = state.page;
        h.inv = Bukkit.createInventory(h, 54, Component.text("ᴡᴏʀᴛʜ", NamedTextColor.GRAY));
        render(h);
        p.openInventory(h.inv);
    }

    private void render(Holder h) {
        String q = h.search.toLowerCase(Locale.ROOT).replace('_', ' ').trim();

        List<Map.Entry<Material, Double>> list = new ArrayList<>();
        for (Map.Entry<Material, Double> en : prices.all().entrySet()) {
            if (!en.getKey().isItem()) continue;
            if (!q.isEmpty() && !PriceManager.pretty(en.getKey()).toLowerCase(Locale.ROOT).contains(q)) continue;
            list.add(en);
        }

        Comparator<Map.Entry<Material, Double>> byName =
                Comparator.comparing(en -> PriceManager.pretty(en.getKey()));
        switch (h.sort) {
            case ALPHABETICAL -> list.sort(byName);
            case HIGH_LOW -> list.sort(Comparator.<Map.Entry<Material, Double>>comparingDouble(Map.Entry::getValue)
                    .reversed().thenComparing(byName));
            case LOW_HIGH -> list.sort(Comparator.<Map.Entry<Material, Double>>comparingDouble(Map.Entry::getValue)
                    .thenComparing(byName));
        }

        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PER_PAGE));
        h.page = Math.max(0, Math.min(h.page, pages - 1));

        Inventory inv = h.inv;
        inv.clear();

        int start = h.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < list.size(); i++) {
            Map.Entry<Material, Double> en = list.get(start + i);
            ItemStack it = new ItemStack(en.getKey());
            ItemMeta meta = it.getItemMeta();
            meta.lore(List.of(PriceManager.worthLine(en.getValue())));
            meta.getPersistentDataContainer().set(prices.noLoreKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }

        // bottom bar
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);

        inv.setItem(SLOT_PREV, button(Material.ARROW,
                Component.text("Previous Page", NamedTextColor.YELLOW),
                Component.text("Page " + (h.page + 1) + " / " + pages, NamedTextColor.GRAY)));

        inv.setItem(SLOT_SEARCH, button(Material.OAK_SIGN,
                Component.text("Search", NamedTextColor.AQUA),
                Component.text("Current: " + (h.search.isEmpty() ? "none" : h.search), NamedTextColor.GRAY),
                Component.text("Left-click: type a name on a sign", NamedTextColor.DARK_GRAY),
                Component.text("Right-click: clear search", NamedTextColor.DARK_GRAY)));

        inv.setItem(SLOT_CLOSE, button(Material.BARRIER, Component.text("Close", NamedTextColor.RED)));

        inv.setItem(SLOT_SORT, button(Material.HOPPER,
                Component.text("Sort", NamedTextColor.GOLD),
                Component.text("Mode: " + h.sort.label, NamedTextColor.GRAY),
                Component.text("Click to change", NamedTextColor.DARK_GRAY)));

        inv.setItem(SLOT_NEXT, button(Material.ARROW,
                Component.text("Next Page", NamedTextColor.YELLOW),
                Component.text("Page " + (h.page + 1) + " / " + pages, NamedTextColor.GRAY)));
    }

    private ItemStack button(Material mat, Component name, Component... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (Component c : lore) l.add(c.decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        meta.getPersistentDataContainer().set(prices.noLoreKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true); // nothing can be taken or inserted
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        switch (e.getSlot()) {
            case SLOT_PREV -> {
                h.page--;
                render(h);
                click(p);
            }
            case SLOT_NEXT -> {
                h.page++;
                render(h);
                click(p);
            }
            case SLOT_SORT -> {
                h.sort = h.sort.next();
                h.page = 0;
                render(h);
                click(p);
            }
            case SLOT_SEARCH -> {
                if (e.getClick() == ClickType.RIGHT) {
                    h.search = "";
                    h.page = 0;
                    render(h);
                    click(p);
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        p.closeInventory();
                        Bukkit.getScheduler().runTaskLater(plugin, () -> openSignSearch(p, h), 2L);
                    });
                }
            }
            case SLOT_CLOSE -> Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
            default -> { }
        }
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ sign search

    /**
     * Opens a real sign editor. A sign is placed for a moment in a free block at the player's
     * position (the client closes the editor if the sign is out of reach), the player edits it,
     * then the original block is restored.
     */
    private void openSignSearch(Player p, Holder state) {
        if (!p.isOnline()) return;

        World w = p.getWorld();
        int x = p.getLocation().getBlockX();
        int y = p.getLocation().getBlockY();
        int z = p.getLocation().getBlockZ();

        int[][] offsets = {{0, 0, 0}, {0, 1, 0}, {0, 2, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 3, 0}};
        Block block = null;
        for (int[] o : offsets) {
            Block b = w.getBlockAt(x + o[0], y + o[1], z + o[2]);
            boolean used = sessions.values().stream().anyMatch(s -> s.block().equals(b));
            if (!used && b.isReplaceable()) { block = b; break; }
        }
        if (block == null) {
            p.sendMessage(Component.text("No free space here to open the search sign.", NamedTextColor.RED));
            openHolder(p, state);
            return;
        }

        BlockData original = block.getBlockData();
        block.setType(Material.OAK_SIGN, false);

        Sign sign = (Sign) block.getState();
        SignSide side = sign.getSide(Side.FRONT);
        side.line(0, Component.text(state.search));
        side.line(1, Component.text("^^^^^^^^^^^^^^^"));
        side.line(2, Component.text("Type an item name"));
        side.line(3, Component.text("to search"));
        sign.update(true, false);

        final Block placed = block;
        SignSession session = new SignSession(placed, original, state);
        sessions.put(p.getUniqueId(), session);

        p.openSign((Sign) placed.getState(), Side.FRONT);

        // safety net: restore the block if the editor never reports back
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            SignSession cur = sessions.get(p.getUniqueId());
            if (cur == session) {
                sessions.remove(p.getUniqueId());
                restore(session);
            }
        }, 20L * 120);
    }

    private void restore(SignSession s) {
        s.block().setBlockData(s.original(), false);
    }

    @EventHandler
    public void onSignChange(SignChangeEvent e) {
        Player p = e.getPlayer();
        SignSession s = sessions.get(p.getUniqueId());
        if (s == null || !s.block().equals(e.getBlock())) return;

        e.setCancelled(true);
        sessions.remove(p.getUniqueId());

        Component line = e.line(0);
        String text = line == null ? "" : PlainTextComponentSerializer.plainText().serialize(line).trim();

        Bukkit.getScheduler().runTask(plugin, () -> {
            restore(s);
            if (!text.isEmpty()) {
                s.state().search = text;
                s.state().page = 0;
            }
            openHolder(p, s.state());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        SignSession s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) restore(s);
    }

    /** Called on plugin disable: put back any block still replaced by a search sign. */
    public void cleanup() {
        for (SignSession s : sessions.values()) restore(s);
        sessions.clear();
    }
}
