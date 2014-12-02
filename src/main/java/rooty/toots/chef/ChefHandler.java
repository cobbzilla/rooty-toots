package rooty.toots.chef;

import com.fasterxml.jackson.core.JsonParser;
import com.google.common.io.Files;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FileUtils;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.util.system.CommandResult;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyMessage;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.cobbzilla.util.json.JsonUtil.FULL_MAPPER;

@Slf4j
public class ChefHandler extends AbstractChefHandler {

    @Getter @Setter private String group;

    @Override public boolean accepts(RootyMessage message) { return message instanceof ChefMessage; }

    @Override
    public synchronized void process(RootyMessage message) {

        final ChefMessage chefMessage = (ChefMessage) message;
        final File chefDir = new File(getChefDir());

        // todo: Copy entire chef dir to backup dir
        File backup = Files.createTempDir();
        try {
            FileUtils.copyDirectory(chefDir, backup);
        } catch (IOException e) {
            throw new IllegalStateException("Error backing up chef: " + e, e);
        }

        try {
            apply(chefMessage);
        } catch (Exception e) {
            rollback(backup);
            throw new IllegalStateException("Error applying chef change: " + e, e);
        }
    }

    private void apply (ChefMessage chefMessage) throws Exception {

        final File chefDir = new File(getChefDir());
        final File soloJson = new File(chefDir, "solo.json");

        // Read solo.json into an ordered set
        final ChefSolo chefSolo;
        final Set<String> recipes;

        // Load original solo.json data
        FULL_MAPPER.getFactory().enable(JsonParser.Feature.ALLOW_COMMENTS);
        chefSolo = JsonUtil.FULL_MAPPER.readValue(soloJson, ChefSolo.class);
        recipes = new LinkedHashSet<>(Arrays.asList(chefSolo.getRun_list()));

        if (chefMessage.isAdd()) {
            // todo: validate chefMessage.chefDir -- ensure it is valid structure; do not allow writes to core cookbooks and data bags

            // copy chef overlay into main chef repo
            FileUtils.copyDirectory(new File(chefMessage.getChefDir()), chefDir);

            // add recipes to run list
            for (String recipe : chefMessage.getRecipes()) recipes.add(recipe);

        } else if (chefMessage.isRemove()) {
            // for now we do not remove cookbooks -- that would require ensuring that no other cookbook
            // used by a recipe in the run_list still requires it

            // remove recipes from solo.json
            for (String recipe : chefMessage.getRecipes()) recipes.remove(recipe);
        }

        // write solo.json with updated run list
        chefSolo.setRun_list(recipes.toArray(new String[recipes.size()]));
        JsonUtil.FULL_MAPPER.writeValue(soloJson, chefSolo);

        // run chef-solo
        runChefSolo();
    }

    protected void runChefSolo() throws Exception {
        final File chefDir = new File(getChefDir());
        // todo: log stdout/stderr somewhere for debugging
        final CommandLine chefSoloCommand = new CommandLine("sudo").addArgument("bash").addArgument("install.sh");
        final CommandResult result = CommandShell.exec(chefSoloCommand, chefDir);
        if (result.hasException()) throw result.getException();
        if (!result.isZeroExitStatus()) throw new IllegalStateException("chef-solo exited with non-zero value: "+result.getExitStatus());
    }

    private void rollback(File backupDir) {
        try {
            final File chefDir = new File(getChefDir());
            FileUtils.copyDirectory(new File(backupDir, chefDir.getName()), chefDir);

        } catch (IOException e) {
            log.error("Error rolling back solo.json: "+e);
        }
    }

}
