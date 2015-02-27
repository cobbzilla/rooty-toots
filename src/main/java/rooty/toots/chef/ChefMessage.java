package rooty.toots.chef;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.ArrayUtils;
import org.cobbzilla.util.security.ShaUtil;
import rooty.RootyMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.cobbzilla.util.string.StringUtil.empty;

@NoArgsConstructor @ToString @Accessors(chain=true)
public class ChefMessage extends RootyMessage {

    private static final Pattern RUNLIST_PATTERN = Pattern.compile("recipe\\[([\\w\\-]+)(::([\\w\\-]+))?\\]");

    public ChefMessage(ChefOperation operation) { this.operation = operation; }

    @Getter @Setter private String chefDir;

    @Getter @Setter private ChefOperation operation;
    @JsonIgnore public boolean isAdd () { return ChefOperation.ADD == operation; }
    @JsonIgnore public boolean isRemove () { return ChefOperation.REMOVE == operation; }

    @Getter @Setter private List<String> recipes = new ArrayList<>();

    public ChefMessage setRecipes (String[] recipes) { this.recipes.addAll(Arrays.asList(recipes)); return this; }

    public ChefMessage addRecipe(String recipe) { recipes.add(recipe); return this; }

    // if true, ChefHandler will re-apply this change even if it seems like it was already applied
    @Getter @Setter private boolean forceApply = false;

    public List<String> getCookbooks () {
        List<String> cookbooks = new ArrayList<>();
        for (String recipe : recipes) {
            final String cookbook = getCookbook(recipe);
            if (cookbook != null) {
                cookbooks.add(cookbook);
            } else {
                throw new IllegalArgumentException("Invalid recipe: "+recipe);
            }
        }
        return cookbooks;
    }

    public static String getCookbook(String recipe) {
        final Matcher matcher = RUNLIST_PATTERN.matcher(recipe);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String getRecipe(String recipe) {
        final Matcher matcher = RUNLIST_PATTERN.matcher(recipe);
        return matcher.find() ? empty(matcher.group(3)) ? "default" : matcher.group(3) : null;
    }

    @JsonIgnore public String getFingerprint () {
        return ShaUtil.sha256_hex(operation.name()+"_"+ ArrayUtils.toString(recipes));
    }

}
