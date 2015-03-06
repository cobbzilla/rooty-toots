package rooty.toots.chef;

import com.fasterxml.jackson.core.JsonParser;
import edu.emory.mathcs.backport.java.util.Collections;
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
import java.util.List;

import static org.cobbzilla.util.daemon.ZillaRuntime.die;
import static org.cobbzilla.util.io.FileUtil.abs;
import static org.cobbzilla.util.io.FileUtil.mkdirOrDie;
import static org.cobbzilla.util.json.JsonUtil.FULL_MAPPER;
import static org.cobbzilla.util.json.JsonUtil.fromJson;
import static rooty.toots.chef.ChefSolo.SOLO_JSON;

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
            final File origChefDir = new File(abs(chefDir)); // todo: can this just be "chefDir" ?? why not?
            final File backupDir = new File(chefDir.getParentFile(), ".backup"+dstamp()+System.currentTimeMillis());
            if (!chefDir.renameTo(backupDir)) {
                final String msg = "process: Error renaming chefDir (" + abs(chefDir) + ") to backup (" + abs(backupDir) + ")";
                message.setError(msg);
                die(msg);
            }
            if (!staging.renameTo(origChefDir)) {
                // whoops! rename backup
                if (!backupDir.renameTo(origChefDir)) {
                    final String msg = "process: Error rolling back!";
                    message.setError(msg);
                    die(msg);
                }
                final String msg = "Error moving staging dir into place, successfully restore chef-solo dir from backup dir";
                message.setError(msg);
                die(msg);
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
        return new File(mkdirOrDie(new File(chefDir, "applied")), chefMessage.getFingerprint());
    }

    private void apply (ChefMessage chefMessage, File chefStaging) throws Exception {

        final File soloJson = new File(getChefDir(), SOLO_JSON);

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
            JsonUtil.FULL_MAPPER.writeValue(new File(chefStaging, SOLO_JSON), mergedChefSolo);

        } else if (chefMessage.isRemove()) {
            // for now we do not remove cookbooks -- that would require ensuring that no other cookbook
            // used by a recipe in the run_list still requires it

            // Generate a new runlist for uninstalling. First include all ::lib recipes
            updateChefSolo.addRecipes(currentChefSolo.getLibRecipeRunList(chefStaging, Collections.emptyList()));

            // Then include ::uninstall recipes for things found in the ChefMessage
            for (String toRemove : chefMessage.getRecipes()) {
                final ChefSoloEntry entry = new ChefSoloEntry(toRemove);
                // ensure uninstall exists
                if (new File(abs(chefStaging)+"/cookbooks/"+entry.getCookbook()+"/recipes/uninstall.rb").exists()) {
                    updateChefSolo.add(new ChefSoloEntry(entry.getCookbook(), "uninstall").toString());
                } else {
                    log.warn("No uninstall recipe found for "+toRemove);
                }
            }

            // Lastly, add in ::validate recipes for anything in the original run_list that is NOT in the ChefMessage
            final List<String> cookbooksRemoved = chefMessage.getCookbooks();
            for (ChefSoloEntry entry : currentChefSolo.getEntries()) {
                if (entry.getRecipe().equals("validate") && !cookbooksRemoved.contains(entry.getCookbook())) {
                    updateChefSolo.add(entry.toString());
                }
            }

            // write solo.json with updated run list
            @Cleanup("delete") final File tempSolo = File.createTempFile("chef-solo-", ".json", chefStaging);
            JsonUtil.FULL_MAPPER.writeValue(tempSolo, updateChefSolo);

            // run chef-solo to uninstall
            runChefSolo(chefStaging, tempSolo, chefMessage);

            // successfully ran, so remove message recipes from the run list
            currentChefSolo.removeRecipes(chefMessage.getRecipes());
            JsonUtil.FULL_MAPPER.writeValue(new File(chefStaging, SOLO_JSON), currentChefSolo);

        } else {
            throw new IllegalArgumentException("Invalid chefMessage (neither add nor remove): "+chefMessage);
        }
    }

    protected boolean useSudo () { return true; }

    protected void runChefSolo(File chefDir, File runlist, ChefMessage chefMessage) throws Exception {
        CommandLine chefSoloCommand = new CommandLine("sudo").addArgument("bash").addArgument("install.sh");
        final ChefSolo soloRunList;
        if (runlist != null) {
            chefSoloCommand = chefSoloCommand.addArgument(abs(runlist));
            soloRunList = fromJson(FileUtil.toString(runlist), ChefSolo.class);
        } else {
            soloRunList = fromJson(FileUtil.toString(new File(chefDir, SOLO_JSON)), ChefSolo.class);
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
                die("chef-solo exited with non-zero value: " + result.getExitStatus());
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

        final File stagingParent = mkdirOrDie(new File(chefDir.getParentFile(), "staging"));
        final File stagingDir;
        final CommandResult result;
        try {
            stagingDir = mkdirOrDie(new File(stagingParent, "chef" + dstamp() + hash));
            result = rsync(chefDir, stagingDir);

        } catch (Exception e) {
            return die("Error backing up chef: " + e, e);
        }
        if (!result.isZeroExitStatus()) return die("Error creating staging dir: "+result);
        return stagingDir;
    }

    protected CommandResult rsync(File from, File to) throws IOException {
        final CommandLine commandLine = useSudo() ? new CommandLine("sudo").addArgument("rsync") : new CommandLine("rsync");
        final CommandResult result = CommandShell.exec(commandLine
                .addArgument("-ac")
                .addArgument(abs(from) + "/")
                .addArgument(abs(to)));
        if (useSudo()) {
            final CommandLine chown = new CommandLine("sudo")
                    .addArgument("chown")
                    .addArgument("-R")
                    .addArgument(getChefUser())
                    .addArgument(abs(to));
            final CommandResult chownResult = CommandShell.exec(chown);
            if (!chownResult.isZeroExitStatus()) {
                die("Error chown'ing destination dir "+to+" to "+getChefUser()+": "+chownResult);
            }
        }
        return result;
    }

}
