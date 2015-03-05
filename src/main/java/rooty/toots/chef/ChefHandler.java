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
import org.cobbzilla.util.system.Command;
import org.cobbzilla.util.system.CommandProgressFilter;
import org.cobbzilla.util.system.CommandResult;
import org.cobbzilla.util.system.CommandShell;
import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import rooty.RootyMessage;

import java.io.File;
import java.io.IOException;

import static org.cobbzilla.util.json.JsonUtil.FULL_MAPPER;
import static org.cobbzilla.util.json.JsonUtil.fromJson;

@Slf4j
public class ChefHandler extends AbstractChefHandler {

    @Getter @Setter private String group;

    private static final DateTimeFormatter DFORMAT = DateTimeFormat.forPattern("_yyyyMMdd_");
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
            final File backupDir = new File(chefDir.getParentFile(), ".backup"+dstamp()+System.currentTimeMillis());
            if (!chefDir.renameTo(backupDir)) {
                final String msg = "process: Error renaming chefDir (" + chefDir.getAbsolutePath() + ") to backup (" + backupDir.getAbsolutePath() + ")";
                message.setError(msg);
                throw new IllegalStateException(msg);
            }
            if (!staging.renameTo(origChefDir)) {
                // whoops! rename backup
                if (!backupDir.renameTo(origChefDir)) {
                    final String msg = "process: Error rolling back!";
                    message.setError(msg);
                    throw new IllegalStateException(msg);
                }
                final String msg = "Error moving staging dir into place, successfully restore chef-solo dir from backup dir";
                message.setError(msg);
                throw new IllegalStateException(msg);
            }

            // write the fingerprint to the "applied" directory
            FileUtil.toFileOrDie(fpFile, JsonUtil.toJsonOrDie(chefMessage));

        } catch (Exception e) {
            FileUtils.deleteQuietly(staging);
            final String msg = "process: Error applying chef change: " + e;
            message.setError(msg);
            log.error(msg, e);
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
            updateChefSolo.addRecipes(currentChefSolo.getValidationRunList(chefStaging, chefMessage.getRecipes()));

            // write solo.json with updated run list
            @Cleanup("delete") final File tempSolo = File.createTempFile("chef-solo-", ".json", chefStaging);
            JsonUtil.FULL_MAPPER.writeValue(tempSolo, updateChefSolo);

            // run chef-solo
            runChefSolo(chefStaging, tempSolo, chefMessage);

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

    protected void runChefSolo(File chefDir, File runlist, ChefMessage chefMessage) throws Exception {
        CommandLine chefSoloCommand = new CommandLine("sudo").addArgument("bash").addArgument("install.sh");
        final ChefSolo soloRunList;
        if (runlist != null) {
            chefSoloCommand = chefSoloCommand.addArgument(runlist.getAbsolutePath());
            soloRunList = fromJson(FileUtil.toString(runlist), ChefSolo.class);
        } else {
            soloRunList = fromJson(FileUtil.toString(new File(chefDir, "solo.json")), ChefSolo.class);
        }

        final Command chefCommand = new Command(chefSoloCommand)
                .setCopyToStandard(true)
                .setDir(chefDir)
                .setOut(getChefProgressFilter(soloRunList, chefMessage));

        final CommandResult result;
        try {
            result = CommandShell.exec(chefCommand);
            if (result.hasException()) throw result.getException();
            if (!result.isZeroExitStatus())
                throw new IllegalStateException("chef-solo exited with non-zero value: " + result.getExitStatus());
        } finally {
            log.info("chef run completed");
        }
    }

    private CommandProgressFilter getChefProgressFilter(ChefSolo runList, ChefMessage chefMessage) {

        final CommandProgressFilter filter = new CommandProgressFilter()
                .setCallback(new ChefProgressCallback(chefMessage, getQueueName(), getStatusManager()))
                .addIndicator("INFO: Chef-client pid", 1);

        // Skip all "lib" recipes
        int numEntries = 0;
        for (ChefSoloEntry entry : runList.getEntries()) {
            if (!entry.getRecipe().equals("lib")) numEntries++;
        }

        // Leave 20% for the last item (typically a validation step)
        final int entryDelta = 80 / numEntries;
        int pct = 0;
        for (ChefSoloEntry entry : runList.getEntries()) {
            if (!entry.getRecipe().equals("lib")) {
                pct += entryDelta;
                filter.addIndicator(getChefProgressPattern(entry), pct);
                // todo: check data_bags/app/progress_markers.json for progress markers to add, distribute pro-rata
            }
        }
        filter.addIndicator("INFO: Chef Run complete", 100);
        return filter;
    }

    public static String getChefProgressPattern(ChefSoloEntry entry) {
        return "\\(" + entry.getCookbook() + "::" + entry.getFullRecipeName() + " line \\d+\\)$";
    }

    private File createStagingDir(File chefDir, String hash) {

        final File stagingParent = new File(chefDir.getParentFile(), "staging");
        if (!stagingParent.exists() && !stagingParent.mkdirs()) {
            throw new IllegalStateException("Error creating staging dir: "+stagingParent.getAbsolutePath());
        }

        final File stagingDir;
        final CommandResult result;
        try {
            stagingDir = new File(stagingParent, "chef" + dstamp() + hash);
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
