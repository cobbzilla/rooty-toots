package rooty.toots.chef;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rooty.RootyMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@NoArgsConstructor
public class ChefMessage extends RootyMessage {

    private static final Pattern COOKBOOK_FROM_RECIPE_PATTERN = Pattern.compile("recipe\\[(\\w+)(::\\w+)?\\]");

    public ChefMessage(ChefOperation operation) { this.operation = operation; }

    @Getter @Setter private String chefDir;

    @Getter @Setter private ChefOperation operation;
    @JsonIgnore public boolean isAdd () { return ChefOperation.ADD == operation; }
    @JsonIgnore public boolean isRemove () { return ChefOperation.REMOVE == operation; }

    @Getter @Setter private List<String> recipes = new ArrayList<>();
    public void addRecipe(String recipe) { recipes.add(recipe); }

    public List<String> getCookbooks () {
        List<String> cookbooks = new ArrayList<>();
        for (String recipe : recipes) {
            final Matcher matcher = COOKBOOK_FROM_RECIPE_PATTERN.matcher(recipe);
            if (matcher.find()) {
                cookbooks.add(matcher.group(1));
            } else {
                throw new IllegalArgumentException("Invalid recipe: "+recipe);
            }
        }
        return cookbooks;
    }

}
