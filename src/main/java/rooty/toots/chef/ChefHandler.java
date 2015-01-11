package rooty.toots.chef;

import com.fasterxml.jackson.core.JsonParser;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FileUtils;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.util.system.CommandResult;
import org.cobbzilla.util.system.CommandShell;
import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import rooty.RootyMessage;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.cobbzilla.util.json.JsonUtil.FULL_MAPPER;

@Slf4j
public class ChefHandler extends AbstractChefHandler {

    @Getter @Setter private String group;

    @Override public boolean accepts(RootyMessage message) { return message instanceof ChefMessage; }

    @Override
    public synchronized boolean process(RootyMessage message) {

        final ChefMessage chefMessage = (ChefMessage) message;
        final File chefDir = new File(getChefDir());

        // have we already applied this change?
        final File fpFile = getFingerprintFile(chefDir, chefMessage);
        if (fpFile.exists() && !chefMessage.isForceApply()) {
            log.warn("Change already applied and forceApply == false, not reapplying: "+chefMessage);
            return true;
        }

        // copy entire chef dir to backup dir
        final File backup = backup(chefDir, chefMessage.getFingerprint());

        try {
            apply(chefMessage);

            // write the fingerprint to the "applied" directory
            FileUtil.toFileOrDie(fpFile, JsonUtil.toJsonOrDie(chefMessage));

        } catch (Exception e) {
            rollback(backup, chefDir);
            throw new IllegalStateException("Error applying chef change: " + e, e);
        }
        return true;
    }

    private File getFingerprintFile(File chefDir, ChefMessage chefMessage) {
        final File fpDir = new File(chefDir, "applied");
        if (!fpDir.exists() && !fpDir.mkdirs()) {
            throw new IllegalStateException("Error creating fingerprints dir: "+fpDir.getAbsolutePath());
        }

        return new File(fpDir, chefMessage.getFingerprint());
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

    protected void runChefSolo() throws Exception { runChefSolo(null); }

    protected void runChefSolo(String runlist) throws Exception {
        final File chefDir = new File(getChefDir());
        // todo: log stdout/stderr somewhere for debugging
        CommandLine chefSoloCommand = new CommandLine("sudo").addArgument("bash").addArgument("install.sh");
        if (runlist != null) chefSoloCommand = chefSoloCommand.addArgument(runlist);

        final CommandResult result = CommandShell.exec(chefSoloCommand, chefDir);
        if (result.hasException()) throw result.getException();
        if (!result.isZeroExitStatus()) throw new IllegalStateException("chef-solo exited with non-zero value: "+result.getExitStatus());
    }

    private static final DateTimeFormatter DFORMAT = DateTimeFormat.forPattern("_yyyyMMdd_");

    private File backup(File chefDir, String hash) {

        final File backupsDir = new File(chefDir.getParentFile(), "backups");
        if (!backupsDir.exists() && !backupsDir.mkdirs()) {
            throw new IllegalStateException("Error creating backups dir: "+backupsDir.getAbsolutePath());
        }

        final File backup;
        final CommandResult result;
        try {
            backup = new File(backupsDir, "backup" + LocalDate.now().toString(DFORMAT) + hash);
            if (!backup.exists() && !backup.mkdirs()) {
                throw new IllegalArgumentException("Error creating backup dir: "+backup.getAbsolutePath());
            }
            result = CommandShell.exec(backupCommand(chefDir, backup));

        } catch (Exception e) {
            throw new IllegalStateException("Error backing up chef: " + e, e);
        }
        if (!result.isZeroExitStatus()) throw new IllegalStateException("Error backing up chef: "+result);
        return backup;
    }

    private void rollback(File backupDir, File chefDir) {
        final CommandResult result;
        try {
            result = CommandShell.exec(rollbackCommand(backupDir, chefDir));

            // re-run chef-solo with only the validate run-list, to sync cloudos app-repository back to previous state
            runChefSolo("solo-validate.json");

        } catch (Exception e) {
            throw new IllegalStateException("Error rolling back chef: " + e, e);
        }
        if (!result.isZeroExitStatus()) throw new IllegalStateException("Error rolling back chef: "+result);
    }

    protected CommandLine backupCommand(File chefDir, File backup) {
        return new CommandLine("sudo")
                .addArgument("rsync")
                .addArgument("-ac")
                .addArgument(chefDir.getAbsolutePath())
                .addArgument(backup.getAbsolutePath());
    }

    protected CommandLine rollbackCommand(File backupDir, File chefDir) {
        return new CommandLine("sudo")
                .addArgument("rsync")
                .addArgument("-ac")
                .addArgument(backupDir.getAbsolutePath()+"/"+chefDir.getName())
                .addArgument(chefDir.getParentFile().getAbsolutePath());
    }

}
