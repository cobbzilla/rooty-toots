package rooty.toots.chef;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.File;
import java.util.*;

@NoArgsConstructor @AllArgsConstructor
public class ChefSolo {

    @Getter @Setter private List<String> run_list = new ArrayList<>();

    public void setRun_list(String[] run_list) { this.run_list.addAll(Arrays.asList(run_list)); }

    @JsonIgnore public Set<ChefSoloEntry> getEntries () { return getEntries(run_list); }

    public static Set<ChefSoloEntry> getEntries(List<String> list) {
        final Set<ChefSoloEntry> entries = new LinkedHashSet<>(list.size());
        for (String recipe : list) {
            entries.add(new ChefSoloEntry(recipe));
        }
        return entries;
    }

    @JsonIgnore public Set<String> getCookbooks () {
        final Set<String> cookbooks = new LinkedHashSet<>(run_list.size());
        for (String recipe : run_list) {
            cookbooks.add(new ChefSoloEntry(recipe).getCookbook());
        }
        return cookbooks;
    }

    public Set<String> getLibRecipeRunList(File chefDir, List<String> includeRecipes) {
        return getRunList(chefDir, "lib", includeRecipes);
    }

    public Set<String> getDefaultRunList(File chefDir, List<String> includeRecipes) {
        return getRunList(chefDir, "default", includeRecipes);
    }

    public Set<String> getValidationRunList(File chefDir, List<String> includeRecipes) {
        return getRunList(chefDir, "validate", includeRecipes);
    }

    public Set<String> getRunList(File chefDir, String recipeName) {
        return getRunList(chefDir, recipeName, null);
    }

    public Set<String> getRunList(File chefDir, String recipeName, List<String> includeRecipes) {
        final Set<String> found = new LinkedHashSet<>(run_list.size());
        for (String cookbook : getCookbooks()) {
            if (recipeExists(chefDir, cookbook, recipeName)) {
                final ChefSoloEntry soloEntry = new ChefSoloEntry(cookbook, recipeName);

                // only add this entry if the run_list also includes the default recipe for this cookbook
                // there might be other stuff in cookbooks dir that should not be active,
                // so we don't include it unless the default recipe is found in the current run_list
                if (hasDefaultRecipe(cookbook)) found.add(soloEntry.toString());
            }
        }

        if (includeRecipes != null) {
            for (String r : includeRecipes) {
                final ChefSoloEntry soloEntry = new ChefSoloEntry(r).setRecipe(recipeName);
                if (recipeExists(chefDir, soloEntry.getCookbook(), recipeName)) {
                    found.add(soloEntry.toString());
                }
            }
        }
        return found;
    }

    private boolean hasDefaultRecipe(String cookbook) {
        return run_list.contains(ChefSoloEntry.defaultRecipe(cookbook))
                || run_list.contains("recipe["+cookbook+"::default]");
    }

    public boolean recipeExists(File chefDir, String cookbook, String recipeName) {
        return new File(chefDir.getAbsolutePath() + "/cookbooks/"+cookbook+"/recipes/"+recipeName+".rb").exists();
    }

    public boolean containsCookbook (String cookbook) { return getCookbooks().contains(cookbook); }

    public void add(String recipe) { run_list.add(recipe); }

    public void addRecipes(Collection<String> recipes) { run_list.addAll(recipes); }

    public void removeRecipes(List<String> recipes) {
        final Set<ChefSoloEntry> entries = getEntries();
        final Set<ChefSoloEntry> entriesToRemove = getEntries(recipes);
        for (ChefSoloEntry toRemove : entriesToRemove) {
            for (Iterator<ChefSoloEntry> i = entries.iterator(); i.hasNext();) {
                if (i.next().getCookbook().equals(toRemove.getCookbook())) i.remove();
            }
        }
        run_list = toRunList(entries);
    }

    public static List<String> toRunList(Set<ChefSoloEntry> entries) {
        final List<String> list = new ArrayList<>(entries.size());
        for (ChefSoloEntry e : entries) {
            list.add(e.toString());
        }
        return list;
    }

    public ChefSolo mergeRunList(List<String> recipes, File chefDir) {
        final List<String> runlist = new ArrayList<>();
        for (String r : getLibRecipeRunList(chefDir, recipes)) runlist.add(r);
        for (String r : getDefaultRunList(chefDir, recipes)) runlist.add(r);
        for (String r : getValidationRunList(chefDir, null)) runlist.add(r);
        return new ChefSolo(runlist);
    }

}
