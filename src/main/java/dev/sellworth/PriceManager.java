package dev.sellworth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

/** Loads prices.yml. The single source of truth for /sell, /worth and the hover tooltip. */
public class PriceManager {

    /** #00a2ff */
    public static final TextColor BLUE = TextColor.color(0x00a2ff);
    /** bright green (&a) */
    public static final TextColor GREEN = NamedTextColor.GREEN;

    private final JavaPlugin plugin;
    /** Items carrying this key already show their own worth lore, so the hover listener skips them. */
    public final NamespacedKey noLoreKey;
    private volatile Map<Material, Double> prices = Collections.emptyMap();
    private volatile double minDamagedFactor = 0.10;

    public PriceManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.noLoreKey = new NamespacedKey(plugin, "no_worth_lore");
    }

    public void load() {
        plugin.reloadConfig();
        minDamagedFactor = Math.max(0, Math.min(100,
                plugin.getConfig().getDouble("damaged-minimum-percent", 10))) / 100.0;

        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Map<Material, Double> map = new HashMap<>();
        // Supports both layouts: nested under "prices:" or flat at the top level
        ConfigurationSection sec = cfg.getConfigurationSection("prices");
        if (sec == null) sec = cfg;

        for (String key : sec.getKeys(false)) {
            Object raw = sec.get(key);
            if (!(raw instanceof Number)) {
                plugin.getLogger().warning("prices.yml: '" + key + "' is not a number, skipped");
                continue;
            }
            Material m = Material.matchMaterial(key);
            if (m == null) {
                plugin.getLogger().warning("prices.yml: unknown material '" + key + "'");
                continue;
            }
            double v = ((Number) raw).doubleValue();
            if (v > 0) map.put(m, v);
        }
        prices = Collections.unmodifiableMap(map);
        plugin.getLogger().info("Loaded " + prices.size() + " prices.");
    }

    /** Price of one undamaged item, or 0 if it can't be sold. */
    public double getPrice(Material m) {
        return prices.getOrDefault(m, 0.0);
    }

    public Map<Material, Double> all() {
        return prices;
    }

    /** 1.0 for a new item, lower the more durability has been used (never below the configured minimum). */
    public double conditionFactor(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable d && d.hasDamage()) {
            int max = d.hasMaxDamage() ? d.getMaxDamage() : item.getType().getMaxDurability();
            if (max > 0) {
                double f = (max - d.getDamage()) / (double) max;
                return Math.max(minDamagedFactor, Math.min(1.0, f));
            }
        }
        return 1.0;
    }

    /** Value of the whole stack, taking damage into account. 0 if unsellable. */
    public double valueOf(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        double unit = getPrice(item.getType());
        if (unit <= 0) return 0;
        return round(unit * conditionFactor(item) * item.getAmount());
    }

    /** Exact name lookup: "wooden sword" / "WOODEN_SWORD" -> Material, or null. */
    public Material find(String query) {
        String q = query.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem()) continue;
            if (pretty(m).toLowerCase(Locale.ROOT).equals(q)) return m;
        }
        return null;
    }

    /** Priced items whose name contains the query. */
    public List<Material> search(String query) {
        String q = query.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        List<Material> out = new ArrayList<>();
        for (Material m : prices.keySet()) {
            if (m.isItem() && pretty(m).toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        }
        out.sort(Comparator.comparing(PriceManager::pretty));
        return out;
    }

    /** "Worth: $X" with Worth in #00a2ff and the $ amount in bright green. */
    public static Component worthLine(double amount) {
        return Component.text("Worth: ", BLUE)
                .append(Component.text("$" + format(amount), GREEN))
                .decoration(TextDecoration.ITALIC, false);
    }

    public static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static String format(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    /** ENDER_PEARL -> Ender Pearl */
    public static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String part : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
