package rooty.toots.chef;

import com.google.common.io.Files;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FileUtils;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.util.string.StringUtil;
import org.cobbzilla.util.system.CommandResult;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
public class ChefHandler extends RootyHandlerBase {

    @Getter @Setter private String chefRepo;
    @Getter @Setter private String group;

    @Override public boolean accepts(RootyMessage message) { return message instanceof ChefMessage; }

    public void write(ChefMessage message, String secret, File tempDir) {
        final File cookbooksDir = Files.createTempDir();
        try {
            for (String cookbook : message.getCookbooks()) {
                final File destDir = new File(cookbooksDir, cookbook);
                FileUtils.copyDirectory(new File(tempDir.getAbsolutePath() + "/chef/cookbooks/" + cookbook), destDir);
            }
            if (!StringUtil.empty(group)) {
                CommandShell.chgrp(group, cookbooksDir, true);
            }
            CommandShell.chmod(cookbooksDir, "770", true);

        } catch (Exception e) {
            throw new IllegalStateException("Error copying cookbooks: "+e, e);
        }

        message.setCookbooksDir(cookbooksDir.getAbsolutePath());
        super.write(message, secret);
    }

    @Override
    public synchronized void process(RootyMessage message) {

        final ChefMessage chefMessage = (ChefMessage) message;
        final File soloJson = new File(chefRepo, "solo.json");
        final String origData;

        // Read solo.json into an ordered set
        final ChefSolo chefSolo;
        final Set<String> recipes;
        try {
            // strip out comments (lines beginning with //)
            origData = FileUtil.toStringExcludingLines(soloJson, "//");
            chefSolo = JsonUtil.FULL_MAPPER.readValue(origData, ChefSolo.class);
            recipes = new LinkedHashSet<>(Arrays.asList(chefSolo.getRun_list()));
        } catch (IOException e) {
            throw new IllegalStateException("Error reading solo.json: "+e, e);
        }

        if (chefMessage.isAdd()) {
            // copy cookbooks into main chef repo
            final File cookbooksDir = new File(chefMessage.getCookbooksDir());
            try {
                for (String cookbook : chefMessage.getCookbooks()) {
                    FileUtils.copyDirectory(new File(cookbooksDir, cookbook),
                            new File(chefRepo + "/cookbooks/" + cookbook));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Error copying cookbooks: " + e, e);
            }

            // add recipes to solo.json
            for (String recipe : chefMessage.getRecipes()) {
                recipes.add(recipe);
            }

        } else if (chefMessage.isRemove()) {
            // for now we do not remove cookbooks -- that would require ensuring that no other cookbook
            // used by a recipe in the run_list still requires it

            // remove recipes from solo.json
            for (String recipe : chefMessage.getRecipes()) {
                recipes.remove(recipe);
            }
        }

        // rewrite solo.json with new recipe list
        chefSolo.setRun_list(recipes.toArray(new String[recipes.size()]));
        try {
            final File temp = File.createTempFile("solo", ".json", soloJson.getParentFile());
            JsonUtil.FULL_MAPPER.writeValue(temp, chefSolo);
            if (!temp.renameTo(soloJson)) {
                throw new IOException("error renaming temp ("+temp.getAbsolutePath()+") to solo.json ("+soloJson.getAbsolutePath()+")");
            }

        } catch (IOException e) {
            rollback(soloJson, origData);
            throw new IllegalStateException("Error writing solo.json: "+e, e);
        }

        // run chef-solo
        try {
            // todo: log stdout/stderr somewhere for debugging
            final CommandLine chefSoloCommand = new CommandLine("sudo").addArgument("bash").addArgument("install.sh");
            final CommandResult result = CommandShell.exec(chefSoloCommand, new File(chefRepo));
            if (result.hasException()) throw result.getException();
            if (!result.isZeroExitStatus()) throw new IllegalStateException("chef-solo exited with non-zero value: "+result.getExitStatus());

        } catch (Exception e) {
            rollback(soloJson, origData);
            throw new IllegalStateException("Error running chef-solo: "+e, e);
        }

    }

    private void rollback(File soloJson, String origData) {
        try {
            FileUtil.toFile(soloJson, origData);
        } catch (IOException e) {
            log.error("Error rolling back solo.json: "+e);
        }
    }

}
