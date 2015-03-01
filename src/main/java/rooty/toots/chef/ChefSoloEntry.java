package rooty.toots.chef;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import static org.cobbzilla.util.string.StringUtil.empty;

@NoArgsConstructor @AllArgsConstructor @Accessors(chain=true)
public class ChefSoloEntry {

    public static String defaultRecipe(String cookbook) { return "recipe["+cookbook+"]"; }

    @Getter @Setter private String cookbook;
    @Getter @Setter private String recipe;

    public ChefSoloEntry (String runListEntry) {
        cookbook = ChefMessage.getCookbook(runListEntry);
        if (cookbook == null) throw new IllegalArgumentException("invalid run list entry: "+runListEntry);
        recipe = ChefMessage.getRecipe(runListEntry);
    }

    public String toString () { return "recipe["+cookbook + (empty(recipe) || recipe.equals("default") ? "" : "::"+recipe) + "]"; }

    public boolean isCookbook(String cookbook) { return this.cookbook.equals(cookbook); }

    public boolean isRecipe(String recipeName) {
        return empty(recipe) ? empty(recipeName) || recipeName.equals("default") : recipe.equals(recipeName);
    }

    public String getFullRecipeName () { return empty(recipe) ? "default" : recipe; }

}
