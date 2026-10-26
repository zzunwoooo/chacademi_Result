package kr.chacademi.chatlayout.mixin;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(CommandSuggestions.SuggestionsList.class)
public interface SuggestionsListAccessor {
    @Accessor("suggestionList") java.util.List<com.mojang.brigadier.suggestion.Suggestion> chacademi$getSuggestions();
    @Accessor("rect") Rect2i chacademi$getRect();
}
