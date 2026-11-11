package org.Cry_Legende.sHCustomCrafting;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SHCustomCrafting extends JavaPlugin implements Listener, TabExecutor {

    private static final String ADMIN = "shcustomcrafting.admin";
    private static final List<String> SUBCOMMANDS = List.of("reload", "list");

    // Recettes enregistrées par le plugin (pour pouvoir les retirer au rechargement)
    private final List<NamespacedKey> registered = new ArrayList<>();
    // Recettes à afficher dans le livre des joueurs
    private final Set<NamespacedKey> bookRecipes = new HashSet<>();
    // Crafts bloqués : l'objet résultat sert uniquement d'affichage
    private final Set<NamespacedKey> blocked = new HashSet<>();
    // Recettes qui n'acceptent l'ingrédient que si son nom contient un texte
    private final Map<NamespacedKey, String> nameFilters = new HashMap<>();
    // Recettes vanilla retirées par le plugin, gardées pour les rétablir quand elles sortent de la liste
    private final Map<NamespacedKey, Recipe> removedVanilla = new LinkedHashMap<>();
    // Résumé des recettes personnalisées pour /shcustomcrafting list (id -> « type, résultat »)
    private final Map<String, String> recipeSummary = new LinkedHashMap<>();
    private final List<String> blockedSummary = new ArrayList<>();
    private final List<String> unknownDisabled = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final List<String> notRestored = new ArrayList<>();
    private int restored;

    // config.yml tel qu'il a été lu ; un fichier illisible ne le remplace jamais
    private volatile YamlConfiguration settings = new YamlConfiguration();
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        for (String error : reloadAll(true)) {
            getLogger().severe(error.replace('\n', ' '));
        }
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("shcustomcrafting").setExecutor(this);
        getCommand("shcustomcrafting").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        unregisterRecipes();
    }

    /**
     * Relit config.yml et messages.yml, puis réapplique les recettes. Les deux fichiers sont d'abord lus à part : si
     * l'un est illisible, les recettes en place ne bougent pas (sauf au démarrage, où il n'y a rien à conserver).
     *
     * @return une entrée « fichier\nerreur » par fichier illisible, vide si tout a été rechargé
     */
    List<String> reloadAll(boolean startup) {
        List<String> errors = new ArrayList<>();
        YamlConfiguration newSettings = new YamlConfiguration();
        try {
            newSettings.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            newSettings = null;
            errors.add(describe("config.yml", e));
        }
        YamlConfiguration newMessages = null;
        try {
            newMessages = messages.read(newSettings);
        } catch (IOException | InvalidConfigurationException e) {
            errors.add(describe("messages.yml", e));
        }
        if (!errors.isEmpty() && !startup) {
            return errors;
        }
        if (newMessages != null) {
            messages.use(newMessages);
        }
        if (newSettings != null) {
            settings = newSettings;
            loadRecipes();
        }
        return errors;
    }

    private static String describe(String file, Exception e) {
        String reason = String.valueOf(e.getMessage()).strip();
        int newline = reason.indexOf('\n');
        return file + "\n" + (newline < 0 ? reason : reason.substring(0, newline));
    }

    private void problem(String text) {
        getLogger().warning(text);
        problems.add(text);
    }

    private void loadRecipes() {
        Set<NamespacedKey> before = new HashSet<>(registered);
        unregisterRecipes();
        problems.clear();
        recipeSummary.clear();
        blockedSummary.clear();
        unknownDisabled.clear();
        notRestored.clear();
        restored = 0;

        // Recettes vanilla : celles de la liste sont retirées (et mémorisées), celles qui n'y sont plus sont rétablies
        Set<NamespacedKey> disabled = new LinkedHashSet<>();
        for (String raw : settings.getStringList("recettes-desactivees")) {
            NamespacedKey key = NamespacedKey.fromString(raw);
            if (key == null) {
                unknownDisabled.add(raw);
                continue;
            }
            disabled.add(key);
            if (removedVanilla.containsKey(key)) {
                continue;
            }
            Recipe recipe = Bukkit.getRecipe(key);
            if (recipe == null || !Bukkit.removeRecipe(key)) {
                unknownDisabled.add(raw);
                continue;
            }
            removedVanilla.put(key, recipe);
        }
        for (Iterator<Map.Entry<NamespacedKey, Recipe>> it = removedVanilla.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<NamespacedKey, Recipe> entry = it.next();
            if (disabled.contains(entry.getKey())) {
                continue;
            }
            if (Bukkit.addRecipe(entry.getValue())) {
                restored++;
                it.remove();
            } else {
                notRestored.add(entry.getKey().toString());
                it.remove();
            }
        }

        ConfigurationSection blockedSection = settings.getConfigurationSection("crafts-bloques");
        if (blockedSection != null) {
            for (String id : blockedSection.getKeys(false)) {
                ConfigurationSection s = blockedSection.getConfigurationSection(id);
                if (s == null) {
                    problem("Craft bloqué " + id + " : bloc invalide.");
                    continue;
                }
                ItemStack display = displayItem(s.getConfigurationSection("affichage"));
                NamespacedKey key = new NamespacedKey(this, "bloque_" + id);
                if (registerShaped(key, s, display)) {
                    blocked.add(key);
                    blockedSummary.add(id);
                }
            }
        }

        ConfigurationSection recipes = settings.getConfigurationSection("recettes");
        if (recipes != null) {
            for (String id : recipes.getKeys(false)) {
                ConfigurationSection s = recipes.getConfigurationSection(id);
                if (s == null) {
                    problem("Recette " + id + " : bloc invalide.");
                    continue;
                }
                Material result = Material.matchMaterial(s.getString("resultat", ""));
                if (result == null) {
                    problem("Recette " + id + " : résultat inconnu.");
                    continue;
                }
                NamespacedKey key = new NamespacedKey(this, id);
                ItemStack out = new ItemStack(result, Math.max(1, s.getInt("quantite", 1)));
                boolean shaped = "shaped".equalsIgnoreCase(s.getString("type"));
                boolean ok = shaped ? registerShaped(key, s, out) : registerShapeless(key, s, out);
                if (!ok) {
                    continue;
                }
                recipeSummary.put(id, (shaped ? "shaped" : "shapeless") + ", " + result.name().toLowerCase(Locale.ROOT)
                    + (s.getBoolean("livre", true) ? ", livre" : ""));
                if (s.getBoolean("livre", true)) {
                    bookRecipes.add(key);
                }
                String filter = s.getString("nom-contient");
                if (filter != null && !filter.isEmpty()) {
                    nameFilters.put(key, filter.toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String raw : unknownDisabled) {
            getLogger().warning("recettes-desactivees : " + raw + " n'existe pas.");
        }
        // Les joueurs en ligne perdent les recettes qui n'existent plus et découvrent les nouvelles
        Set<NamespacedKey> gone = new HashSet<>(before);
        gone.removeAll(registered);
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (!gone.isEmpty()) {
                p.undiscoverRecipes(gone);
            }
            p.discoverRecipes(bookRecipes);
        });
        getLogger().info(registered.size() + " recettes enregistrées, " + removedVanilla.size() + " recettes vanilla désactivées.");
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
            problem("Recette " + key.getKey() + " : forme ou ingrédients manquants.");
            return false;
        }
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(shape.toArray(new String[0]));
        for (String symbol : ingredients.getKeys(false)) {
            Material material = Material.matchMaterial(ingredients.getString(symbol, ""));
            if (material == null || symbol.length() != 1) {
                problem("Recette " + key.getKey() + " : ingrédient " + symbol + " invalide.");
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
                problem("Recette " + key.getKey() + " : ingrédient " + raw + " inconnu.");
                return false;
            }
            recipe.addIngredient(material);
        }
        return add(recipe, key);
    }

    private boolean add(Recipe recipe, NamespacedKey key) {
        if (!Bukkit.addRecipe(recipe)) {
            problem("Recette " + key.getKey() + " refusée par le serveur (doublon ?).");
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
                messages.send(player, "craft-blocked");
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

    // ------------------------------------------------------------------ commande

    private static boolean can(CommandSender sender, String sub) {
        return sender.hasPermission(ADMIN + "." + sub) || sender.hasPermission(ADMIN);
    }

    private static List<String> allowed(CommandSender sender) {
        return SUBCOMMANDS.stream().filter(sub -> can(sender, sub)).toList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            messages.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub)) {
            if (SUBCOMMANDS.contains(sub)) {
                messages.send(sender, "no-permission");
            } else {
                messages.send(sender, "usage", "%subcommands%", String.join("|", allowed));
            }
            return true;
        }
        if (sub.equals("reload")) {
            reload(sender);
        } else {
            list(sender);
        }
        return true;
    }

    private void reload(CommandSender sender) {
        List<String> errors = reloadAll(false);
        if (!errors.isEmpty()) {
            for (String error : errors) {
                int split = error.indexOf('\n');
                messages.send(sender, "reload-error", "%file%", error.substring(0, split), "%error%", error.substring(split + 1));
            }
            return;
        }
        messages.send(sender, "reload-ok", "%count%", String.valueOf(registered.size()));
        messages.send(sender, "reload-details", "%disabled%", String.valueOf(removedVanilla.size()), "%restored%", String.valueOf(restored));
        for (String text : problems) {
            messages.send(sender, "reload-problem", "%detail%", text);
        }
        if (!unknownDisabled.isEmpty()) {
            messages.send(sender, "reload-unknown", "%names%", String.join(", ", unknownDisabled));
        }
        if (!notRestored.isEmpty()) {
            messages.send(sender, "reload-restart", "%names%", String.join(", ", notRestored));
        }
    }

    private void list(CommandSender sender) {
        messages.send(sender, "list-header", "%count%", String.valueOf(recipeSummary.size()));
        for (Map.Entry<String, String> entry : recipeSummary.entrySet()) {
            messages.send(sender, "list-recipe", "%id%", entry.getKey(), "%detail%", entry.getValue());
        }
        messages.send(sender, "list-blocked", "%count%", String.valueOf(blockedSummary.size()),
            "%names%", blockedSummary.isEmpty() ? "-" : String.join(", ", blockedSummary));
        messages.send(sender, "list-vanilla", "%count%", String.valueOf(removedVanilla.size()));
        if (!unknownDisabled.isEmpty()) {
            messages.send(sender, "list-unknown", "%names%", String.join(", ", unknownDisabled));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        return allowed(sender).stream().filter(sub -> sub.startsWith(typed)).toList();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
