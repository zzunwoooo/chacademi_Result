package school.magiccodex.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.context.ContextParameterMap;

/**
 * Recipe list of the crafting panel. Same data as the vanilla recipe book: the recipes the server unlocked for
 * this player (ClientRecipeBook), only those that fit the 2x2 grid, craftability from the player's items plus
 * the grid (vanilla RecipeFinder). Clicking a card sends the vanilla recipe click; the server fills the grid or
 * answers with a ghost (onCraftFailed). Nothing here is trusted by the server.
 */
final class GalaxyRecipes {
    record Card(NetworkRecipeId id, RecipeDisplay display, ItemStack result, String name, boolean craftable) {}

    private final List<Card> all = new ArrayList<>();
    private final List<Card> shown = new ArrayList<>();
    private String query = "";
    private boolean craftableOnly = true;
    private int page;

    List<Card> shown() { return shown; }
    int page() { return page; }
    int pages() { return GalaxyBagLayout.recipePages(shown.size()); }
    boolean craftableOnly() { return craftableOnly; }
    String query() { return query; }

    void craftableOnly(boolean value) { if (craftableOnly != value) { craftableOnly = value; filter(); page = 0; } }
    void query(String value) { if (!query.equals(value)) { query = value; filter(); page = 0; } }
    void turn(int delta) { page = Math.floorMod(page + delta, pages()); }

    /** Card on the current page at index 0..5, or null. */
    Card at(int index) {
        int i = page * GalaxyBagLayout.CARDS_PER_PAGE + index;
        return index >= 0 && index < GalaxyBagLayout.CARDS_PER_PAGE && i < shown.size() ? shown.get(i) : null;
    }

    /** True for crafting recipes that fit the player's 2x2 grid (vanilla AbstractCraftingRecipeBookWidget rule). */
    static boolean fits(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) return shaped.width() <= 2 && shaped.height() <= 2;
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) return shapeless.ingredients().size() <= 4;
        return false;
    }

    static ContextParameterMap context(MinecraftClient client) { return SlotDisplayContexts.createParameters(client.world); }

    /** Rebuild from the client recipe book. Main thread. */
    void rebuild(MinecraftClient client, PlayerScreenHandler handler) {
        all.clear();
        if (client.player == null || client.world == null) { filter(); return; }
        var finder = new RecipeFinder();
        client.player.getInventory().populateRecipeFinder(finder);
        handler.populateRecipeFinder(finder);
        var context = context(client);
        var features = client.world.getEnabledFeatures();
        var seen = new java.util.HashSet<NetworkRecipeId>();
        for (var collection : client.player.getRecipeBook().getOrderedResults()) {
            collection.populateRecipes(finder, GalaxyRecipes::fits);
            for (var entry : collection.getAllRecipes()) {
                RecipeDisplay display = entry.display();
                if (!fits(display) || !display.isEnabled(features) || !seen.add(entry.id())) continue;
                ItemStack result;
                try { result = display.result().getFirst(context); } catch (RuntimeException e) { continue; }
                if (result == null || result.isEmpty()) continue;
                all.add(new Card(entry.id(), display, result, result.getName().getString(), collection.isCraftable(entry.id())));
            }
        }
        int keep = page;
        filter();
        page = Math.min(keep, pages() - 1);
    }

    private void filter() {
        shown.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Card c : all) {
            if (craftableOnly && !c.craftable()) continue;
            if (!q.isEmpty() && !c.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(c);
        }
        // Craftable first when everything is shown, otherwise the book's own order.
        if (!craftableOnly) shown.sort((a, b) -> Boolean.compare(b.craftable(), a.craftable()));
    }

    /**
     * Ghost of a recipe the server could not fill: ingredient stacks per input slot (index 0..3, null = nothing),
     * vanilla placement (shaped rows left to right, shapeless in order).
     */
    static List<List<ItemStack>> ghost(RecipeDisplay display, ContextParameterMap context) {
        var out = new ArrayList<List<ItemStack>>(List.of(List.of(), List.of(), List.of(), List.of()));
        List<SlotDisplay> ingredients;
        int width;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) { ingredients = shaped.ingredients(); width = shaped.width(); }
        else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) { ingredients = shapeless.ingredients(); width = 2; }
        else return out;
        for (int i = 0; i < ingredients.size(); i++) {
            int row = i / Math.max(1, width), col = i % Math.max(1, width);
            if (row > 1 || col > 1) continue;
            List<ItemStack> stacks;
            try { stacks = ingredients.get(i).getStacks(context); } catch (RuntimeException e) { stacks = List.of(); }
            out.set(row * 2 + col, stacks == null ? List.of() : List.copyOf(stacks));
        }
        return out;
    }
}
