package rooty.toots.chef;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.security.ShaUtil;
import org.cobbzilla.util.system.CommandShell;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.cobbzilla.util.daemon.ZillaRuntime.die;
import static org.cobbzilla.util.io.FileUtil.*;
import static org.cobbzilla.util.json.JsonUtil.toJsonOrDie;
import static org.cobbzilla.util.system.CommandShell.chmod;

@NoArgsConstructor @AllArgsConstructor @Slf4j
public class ChefSolo {

    public static final String SOLO_JSON = "solo.json";
    public static final String COOKBOOKS_DIR = "cookbooks";
    public static final String DATABAGS_DIR = "data_bags";
    public static final String DATAFILES_DIR = "data_files";

    @Getter private List<String> run_list = new ArrayList<>();

    public void setRun_list(String[] run_list) { this.run_list.addAll(Arrays.asList(run_list)); }

    /**
     * Return a new ChefSolo with a sorted run list.
     * First the dependencies will be installed, then the priority app, then everything else.
     * During validation, the depenencies are validated first and the priority app is validated last.
     * @param priorityApp the app to install immediately after dependencies have been installed
     * @param dependencies apps to be installed before the priorityApp
     * @return A new ChefSolo with the sorted run list
     */
    public ChefSolo getSortedChefSolo(String priorityApp, List<String> dependencies) {
        final ChefSolo newSolo = new ChefSolo();
        final Set<ChefSoloEntry> entries = getEntries();

        // Lib is normal
        for (ChefSoloEntry entry : entries) {
            if (entry.isRecipe("lib")) {
                newSolo.add(entry.toString());
            }
        }

        // Default recipes -- dependencies first, then priority app, then everything else
        for (String dep : dependencies) {
            newSolo.add(new ChefSoloEntry(dep, "default").toString());
        }
        newSolo.add(new ChefSoloEntry(priorityApp, "default").toString());
        for (ChefSoloEntry entry : entries) {
            if (entry.isRecipe("default")
                    && !entry.getCookbook().equals(priorityApp)
                    && !dependencies.contains(entry.getCookbook())) {
                newSolo.add(entry.toString());
            }
        }

        // Validation -- dependencies first (if they have a validate recipe), priority app last
        for (String dep : dependencies) {
            final ChefSoloEntry validate = new ChefSoloEntry(dep, "validate");
            if (entries.contains(validate)) newSolo.add(validate.toString());
        }
        for (ChefSoloEntry entry : entries) {
            if (entry.isRecipe("validate")
                    && !entry.getCookbook().equals(priorityApp)
                    && !dependencies.contains(entry.getCookbook())) {
                newSolo.add(entry.toString());
            }
        }
        newSolo.add(new ChefSoloEntry(priorityApp, "validate").toString());

        return newSolo;
    }

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

    public static boolean recipeExists(File chefDir, String cookbook, String recipeName) {
        return new File(abs(chefDir) + "/cookbooks/"+cookbook+"/recipes/"+recipeName+".rb").exists();
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

    public static void merge(List<File> chefBaseDirs, File targetDir) throws IOException {

        mkdirOrDie(targetDir);
        final File targetCookbooks = mkdirOrDie(new File(targetDir, COOKBOOKS_DIR));
        final File targetDatabags = mkdirOrDie(new File(targetDir, DATABAGS_DIR));
        final File targetDatafiles = mkdirOrDie(new File(targetDir, DATAFILES_DIR));

        for (File base : chefBaseDirs) {

            FileUtil.assertIsDir(base);
            sync(targetCookbooks, base, COOKBOOKS_DIR);
            sync(targetDatabags, base, DATABAGS_DIR);
            sync(targetDatafiles, base, DATAFILES_DIR);

            for (File f : FileUtil.listFiles(base)) {
                if (f.getName().equals(SOLO_JSON)) continue; // skip solo.json
                final File target = new File(targetDir, f.getName());
                if (!target.exists() || ShaUtil.sha256_file(f).equals(ShaUtil.sha256_file(target))) {
                    FileUtils.copyFile(f, target);
                    if (f.canExecute()) chmod(target, "a+rx");
                }
            }
        }
    }

    protected static void sync(File target, File base, String dir) throws IOException {
        final File files = new File(base, dir);
        if (files.exists() && files.isDirectory()) {
            CommandShell.exec("rsync -avzc " + abs(files) + " " + abs(target.getParentFile()));
        }
    }

    public void write(File file) {
        if (file == null || !file.exists()) die("write: bad file: "+abs(file));
        if (file.isDirectory()) file = new File(file, SOLO_JSON);
        toFileOrDie(file, toJsonOrDie(this));
    }
}
