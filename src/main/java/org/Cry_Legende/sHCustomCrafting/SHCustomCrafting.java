package org.Cry_Legende.sHCustomCrafting;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SHCustomCrafting extends JavaPlugin implements Listener, CommandExecutor {

    // Recettes enregistrées par le plugin (pour pouvoir les retirer au rechargement)
    private final List<NamespacedKey> registered = new ArrayList<>();
    // Recettes à afficher dans le livre des joueurs
    private final Set<NamespacedKey> bookRecipes = new HashSet<>();
    // Crafts bloqués : l'objet résultat sert uniquement d'affichage
    private final Set<NamespacedKey> blocked = new HashSet<>();
    // Recettes qui n'acceptent l'ingrédient que si son nom contient un texte
    private final Map<NamespacedKey, String> nameFilters = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("shcustomcrafting").setExecutor(this);
    }

    @Override
    public void onDisable() {
        unregisterRecipes();
    }

    private void loadRecipes() {
        unregisterRecipes();
        reloadConfig();

        int removed = 0;
        for (String raw : getConfig().getStringList("recettes-desactivees")) {
            NamespacedKey key = NamespacedKey.fromString(raw);
            if (key != null && Bukkit.removeRecipe(key)) {
                removed++;
            }
        }

        ConfigurationSection blockedSection = getConfig().getConfigurationSection("crafts-bloques");
        if (blockedSection != null) {
            for (String id : blockedSection.getKeys(false)) {
                ConfigurationSection s = blockedSection.getConfigurationSection(id);
                ItemStack display = displayItem(s.getConfigurationSection("affichage"));
                NamespacedKey key = new NamespacedKey(this, "bloque_" + id);
                if (registerShaped(key, s, display)) {
                    blocked.add(key);
                }
            }
        }

        ConfigurationSection recipes = getConfig().getConfigurationSection("recettes");
        if (recipes != null) {
            for (String id : recipes.getKeys(false)) {
                ConfigurationSection s = recipes.getConfigurationSection(id);
                Material result = Material.matchMaterial(s.getString("resultat", ""));
                if (result == null) {
                    getLogger().warning("Recette " + id + " : résultat inconnu.");
                    continue;
                }
                NamespacedKey key = new NamespacedKey(this, id);
                ItemStack out = new ItemStack(result, Math.max(1, s.getInt("quantite", 1)));
                boolean ok = "shaped".equalsIgnoreCase(s.getString("type")) ? registerShaped(key, s, out) : registerShapeless(key, s, out);
                if (!ok) {
                    continue;
                }
                if (s.getBoolean("livre", true)) {
                    bookRecipes.add(key);
                }
                String filter = s.getString("nom-contient");
                if (filter != null && !filter.isEmpty()) {
                    nameFilters.put(key, filter.toLowerCase(Locale.ROOT));
                }
            }
        }
        Bukkit.getOnlinePlayers().forEach(p -> p.discoverRecipes(bookRecipes));
        getLogger().info(registered.size() + " recettes enregistrées, " + removed + " recettes vanilla désactivées.");
    }

    private void unregisterRecipes() {
        registered.forEach(Bukkit::removeRecipe);
        registered.clear();
        bookRecipes.clear();
        blocked.clear();
        nameFilters.clear();
    }

    private boolean registerShaped(NamespacedKey key, ConfigurationSection s, ItemStack result) {
        List<String> shape = s.getStringList("forme");
        ConfigurationSection ingredients = s.getConfigurationSection("ingredients");
        if (shape.isEmpty() || ingredients == null) {
            getLogger().warning("Recette " + key.getKey() + " : forme ou ingrédients manquants.");
            return false;
        }
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(shape.toArray(new String[0]));
        for (String symbol : ingredients.getKeys(false)) {
            Material material = Material.matchMaterial(ingredients.getString(symbol, ""));
            if (material == null || symbol.length() != 1) {
                getLogger().warning("Recette " + key.getKey() + " : ingrédient " + symbol + " invalide.");
                return false;
            }
            recipe.setIngredient(symbol.charAt(0), material);
        }
        return add(recipe, key);
    }

    private boolean registerShapeless(NamespacedKey key, ConfigurationSection s, ItemStack result) {
        ShapelessRecipe recipe = new ShapelessRecipe(key, result);
        for (String raw : s.getStringList("ingredients")) {
            Material material = Material.matchMaterial(raw);
            if (material == null) {
                getLogger().warning("Recette " + key.getKey() + " : ingrédient " + raw + " inconnu.");
                return false;
            }
            recipe.addIngredient(material);
        }
        return add(recipe, key);
    }

    private boolean add(Recipe recipe, NamespacedKey key) {
        if (!Bukkit.addRecipe(recipe)) {
            getLogger().warning("Recette " + key.getKey() + " refusée par le serveur (doublon ?).");
            return false;
        }
        registered.add(key);
        return true;
    }

    private ItemStack displayItem(ConfigurationSection s) {
        Material material = s == null ? null : Material.matchMaterial(s.getString("materiau", "BARRIER"));
        ItemStack item = new ItemStack(material == null ? Material.BARRIER : material);
        if (s == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(s.getString("nom", "&cCraft désactivé")));
        meta.setLore(s.getStringList("lore").stream().map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ événements

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed)) {
            return;
        }
        String filter = nameFilters.get(keyed.getKey());
        if (filter != null && !matrixMatches(event.getInventory(), filter)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent event) {
        if (event.getRecipe() instanceof Keyed keyed && blocked.contains(keyed.getKey())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendMessage(color(getConfig().getString("prefix", "") + getConfig().getString("messages.craft-bloque", "")));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipes(bookRecipes);
    }

    /**
     * Tous les objets non vides de la grille doivent porter un nom contenant le texte demandé.
     */
    private static boolean matrixMatches(CraftingInventory inventory, String filter) {
        for (ItemStack item : inventory.getMatrix()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            String name = meta != null && meta.hasDisplayName() ? ChatColor.stripColor(meta.getDisplayName()) : "";
            if (!name.toLowerCase(Locale.ROOT).contains(filter)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        loadRecipes();
        sender.sendMessage(color(getConfig().getString("prefix", "") + getConfig().getString("messages.recharge", "")
            .replace("{count}", String.valueOf(registered.size()))));
        return true;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
