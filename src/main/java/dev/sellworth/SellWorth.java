package dev.sellworth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public class SellWorth extends JavaPlugin {

    private PriceManager prices;
    private SellGui sellGui;
    private WorthGui worthGui;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("No Vault economy provider found (install Vault + an economy plugin such as EssentialsX). Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        prices = new PriceManager(this);
        prices.load();

        sellGui = new SellGui(prices, rsp.getProvider());
        worthGui = new WorthGui(this, prices);
        getServer().getPluginManager().registerEvents(sellGui, this);
        getServer().getPluginManager().registerEvents(worthGui, this);

        if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            try {
                WorthLoreListener.register(this, prices);
            } catch (Throwable t) {
                getLogger().log(java.util.logging.Level.WARNING, "Could not enable hover worth", t);
            }
        } else {
            getLogger().warning("ProtocolLib is NOT installed/enabled: item hover worth is disabled.");
        }
    }

    @Override
    public void onDisable() {
        if (worthGui != null) worthGui.cleanup();
        // return items from any open sell menus
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellGui.Holder) {
                p.closeInventory();
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "sellreload" -> {
                prices.load();
                sender.sendMessage(Component.text("prices.yml reloaded.", NamedTextColor.GREEN));
            }
            case "sell" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                sellGui.open(p);
            }
            case "worth" -> handleWorth(sender, args);
        }
        return true;
    }

    private void handleWorth(CommandSender sender, String[] args) {
        // /worth  -> menu
        if (args.length == 0) {
            if (sender instanceof Player p) worthGui.open(p, "");
            else sender.sendMessage("Usage: /worth <item>");
            return;
        }

        // /worth <item> -> chat + action bar message
        String query = String.join(" ", args).trim();

        Material exact = prices.find(query);
        if (exact != null) {
            sendWorth(sender, exact);
            return;
        }

        List<Material> matches = prices.search(query);
        if (matches.isEmpty()) {
            sender.sendMessage(Component.text("No priced item matches '" + query + "'.", NamedTextColor.RED));
        } else if (matches.size() == 1) {
            sendWorth(sender, matches.get(0));
        } else if (sender instanceof Player p) {
            worthGui.open(p, query); // several matches: show them in the menu
        } else {
            sender.sendMessage(Component.text(matches.size() + " items match '" + query + "'. Be more specific.", NamedTextColor.YELLOW));
        }
    }

    private void sendWorth(CommandSender sender, Material m) {
        double price = prices.getPrice(m);
        Component name = Component.text(PriceManager.pretty(m), PriceManager.BLUE);

        Component msg = price <= 0
                ? name.append(Component.text(" can't be sold.", NamedTextColor.RED))
                : name.append(Component.text(" is worth ", NamedTextColor.WHITE))
                      .append(Component.text("$" + PriceManager.format(price), PriceManager.GREEN));

        sender.sendMessage(msg);
        if (sender instanceof Player p) p.sendActionBar(msg);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("worth") || args.length != 1) return List.of();
        String start = args[0].toLowerCase(Locale.ROOT);
        return prices.all().keySet().stream()
                .filter(Material::isItem)
                .map(m -> m.name().toLowerCase(Locale.ROOT))
                .filter(n -> n.startsWith(start))
                .sorted()
                .limit(50)
                .toList();
    }
}
