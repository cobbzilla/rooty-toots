package rooty.toots.chef;

import com.fasterxml.jackson.core.JsonParser;
import lombok.Cleanup;
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
import java.io.IOException;

import static org.cobbzilla.util.json.JsonUtil.FULL_MAPPER;

@Slf4j
public class ChefHandler extends AbstractChefHandler {

    @Getter @Setter private String group;

    private static String dstamp() { return LocalDate.now().toString(DFORMAT); }

    @Override public boolean accepts(RootyMessage message) { return message instanceof ChefMessage; }

    @Override
    public synchronized boolean process(RootyMessage message) {

        final ChefMessage chefMessage = (ChefMessage) message;
        final File chefDir = new File(getChefDir());

        // have we already applied this change?
        final File fpFile = getFingerprintFile(chefDir, chefMessage);
        if (fpFile.exists() && !chefMessage.isForceApply()) {
            log.warn("process: Change already applied and forceApply == false, not reapplying: "+chefMessage);
            return true;
        }

        // copy entire chef dir to a staging dir, work there
        final File staging = createStagingDir(chefDir, chefMessage.getFingerprint());

        try {
            apply(chefMessage, staging);

            // move current chef dir to backups, move staging in its place
            final File origChefDir = new File(chefDir.getAbsolutePath());
            final File backupDir = new File(chefDir.getParentFile(), ".backup_"+dstamp()+System.currentTimeMillis());
            if (!chefDir.renameTo(backupDir)) {
                throw new IllegalStateException("process: Error renaming chefDir to backup");
            }
            if (!staging.renameTo(origChefDir)) {
                // whoops! rename backup
                if (!backupDir.renameTo(origChefDir)) {
                    throw new IllegalStateException("process: Error rolling back!");
                }
                throw new IllegalStateException("Error moving staging dir into place, successfully restore chef-solo dir from backup dir");
            }

            // write the fingerprint to the "applied" directory
            FileUtil.toFileOrDie(fpFile, JsonUtil.toJsonOrDie(chefMessage));

        } catch (Exception e) {
            FileUtils.deleteQuietly(staging);
            log.error("process: Error applying chef change: " + e, e);
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

    private void apply (ChefMessage chefMessage, File chefStaging) throws Exception {

        final File soloJson = new File(getChefDir(), "solo.json");

        // Load original solo.json data
        FULL_MAPPER.getFactory().enable(JsonParser.Feature.ALLOW_COMMENTS);
        final ChefSolo currentChefSolo = JsonUtil.FULL_MAPPER.readValue(soloJson, ChefSolo.class);
        final ChefSolo updateChefSolo = new ChefSolo();

        if (chefMessage.isAdd()) {
            // todo: validate chefMessage.chefStaging -- ensure it is valid structure; do not allow writes to core cookbooks and data bags

            // copy chef overlay into main chef repo
            rsync(new File(chefMessage.getChefDir()), chefStaging);

            // create a special run list for this "add" operation
            // first include ::lib recipes from all cookbooks currently in the run_list
            updateChefSolo.addRecipes(currentChefSolo.getLibRecipeRunList(chefStaging, chefMessage.getRecipes()));

            // then add the recipes for what we are adding
            updateChefSolo.addRecipes(chefMessage.getRecipes());

            // then add the ::validate recipes
            updateChefSolo.addRecipes(currentChefSolo.getValidationRunList(chefStaging));

            // write solo.json with updated run list
            @Cleanup("delete") final File tempSolo = File.createTempFile("chef-solo-", ".json", chefStaging);
            JsonUtil.FULL_MAPPER.writeValue(tempSolo, updateChefSolo);

            // run chef-solo
            runChefSolo(chefStaging, tempSolo);

            // successfully ran, so add message recipes to run list
            final ChefSolo mergedChefSolo = currentChefSolo.mergeRunList(chefMessage.getRecipes(), chefStaging);
            JsonUtil.FULL_MAPPER.writeValue(new File(chefStaging, "solo.json"), mergedChefSolo);

        } else if (chefMessage.isRemove()) {
            // for now we do not remove cookbooks -- that would require ensuring that no other cookbook
            // used by a recipe in the run_list still requires it

            // remove recipes from solo.json
            currentChefSolo.removeRecipes(chefMessage.getRecipes());
            JsonUtil.FULL_MAPPER.writeValue(new File(chefStaging, "solo.json"), currentChefSolo);

        } else {
            throw new IllegalArgumentException("Invalid chefMessage (neither add nor remove): "+chefMessage);
        }
    }

    protected boolean useSudo () { return true; }

    protected void runChefSolo() throws Exception { runChefSolo(new File(getChefDir()), null); }

    protected void runChefSolo(File chefDir, File runlist) throws Exception {
        // todo: log stdout/stderr somewhere for debugging
        CommandLine chefSoloCommand = new CommandLine("sudo").addArgument("bash").addArgument("install.sh");
        if (runlist != null) chefSoloCommand = chefSoloCommand.addArgument(runlist.getAbsolutePath());

        final CommandResult result = CommandShell.exec(chefSoloCommand, chefDir);
        if (result.hasException()) throw result.getException();
        if (!result.isZeroExitStatus()) throw new IllegalStateException("chef-solo exited with non-zero value: "+result.getExitStatus());
    }

    private static final DateTimeFormatter DFORMAT = DateTimeFormat.forPattern("_yyyyMMdd_");

    private File createStagingDir(File chefDir, String hash) {

        final File stagingParent = new File(chefDir.getParentFile(), "staging");
        if (!stagingParent.exists() && !stagingParent.mkdirs()) {
            throw new IllegalStateException("Error creating staging dir: "+stagingParent.getAbsolutePath());
        }

        final File stagingDir;
        final CommandResult result;
        try {
            stagingDir = new File(stagingParent, "chef-" + dstamp() + hash);
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                throw new IllegalArgumentException("Error creating staging dir: "+stagingDir.getAbsolutePath());
            }
            result = rsync(chefDir, stagingDir);

        } catch (Exception e) {
            throw new IllegalStateException("Error backing up chef: " + e, e);
        }
        if (!result.isZeroExitStatus()) throw new IllegalStateException("Error creating staging dir: "+result);
        return stagingDir;
    }

    protected CommandResult rsync(File from, File to) throws IOException {
        final CommandLine commandLine = useSudo() ? new CommandLine("sudo").addArgument("rsync") : new CommandLine("rsync");
        final CommandResult result = CommandShell.exec(commandLine
                .addArgument("-ac")
                .addArgument(from.getAbsolutePath() + "/")
                .addArgument(to.getAbsolutePath()));
        if (useSudo()) {
            final CommandLine chown = new CommandLine("sudo")
                    .addArgument("chown")
                    .addArgument("-R")
                    .addArgument(getChefUser())
                    .addArgument(to.getAbsolutePath());
            final CommandResult chownResult = CommandShell.exec(chown);
            if (!chownResult.isZeroExitStatus()) {
                throw new IllegalStateException("Error chown'ing destination dir "+to+" to "+getChefUser()+": "+chownResult);
            }
        }
        return result;
    }

}
