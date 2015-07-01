package rooty.toots.chef;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.cobbzilla.util.daemon.ZillaRuntime.die;
import static org.cobbzilla.util.io.FileUtil.abs;
import static org.cobbzilla.util.io.FileUtil.mkdirOrDie;
import static org.cobbzilla.util.json.JsonUtil.FULL_MAPPER_ALLOW_COMMENTS;
import static org.cobbzilla.util.json.JsonUtil.fromJson;
import static rooty.toots.chef.ChefSolo.SOLO_JSON;

@Slf4j
public class ChefHandler extends AbstractChefHandler {

    public static final ObjectMapper JSON = FULL_MAPPER_ALLOW_COMMENTS;

    @Getter @Setter private String group;

    private static final DateTimeFormatter DFORMAT = DateTimeFormat.forPattern("_yyyyMMdd_");
    private static String dstamp() { return LocalDate.now().toString(DFORMAT); }

    @Override public boolean accepts(RootyMessage message) { return message instanceof ChefMessage; }

    @Override
    public synchronized boolean process(RootyMessage message) {

        final ChefMessage chefMessage = (ChefMessage) message;
        final File chefDir = getChefDirFile();

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
            final File backupDir = new File(chefDir.getParentFile(), ".backup"+dstamp()+System.currentTimeMillis());
            if (!chefDir.renameTo(backupDir)) {
                final String msg = "process: Error renaming chefDir (" + abs(chefDir) + ") to backup (" + abs(backupDir) + ")";
                message.setError(msg);
                die(msg);
            }
            if (!staging.renameTo(chefDir)) {
                // whoops! rename backup
                if (!backupDir.renameTo(chefDir)) {
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
        final ChefSolo currentChefSolo = JSON.readValue(soloJson, ChefSolo.class);

        final String cookbook = chefMessage.getCookbook();

        switch (chefMessage.getOperation()) {
            case ADD:
                // copy overlay cookbooks and databags into staging chef repo
                rsync(new File(chefMessage.getChefDir()+"/cookbooks/"+cookbook), new File(abs(chefStaging)+"/cookbooks/"+cookbook));
                rsync(new File(chefMessage.getChefDir()+"/data_bags/"+cookbook), new File(abs(chefStaging)+"/data_bags/"+cookbook));

                // run chef-solo
                runChefSolo(chefStaging, "install", cookbook, chefMessage);

                // successfully ran, so add message recipes to run list
                currentChefSolo.insertApp(cookbook, chefStaging);
                JsonUtil.FULL_MAPPER.writeValue(new File(chefStaging, SOLO_JSON), currentChefSolo);
                break;

            case REMOVE:
                // run chef-solo to uninstall
                runChefSolo(chefStaging, "uninstall", cookbook, chefMessage);

                // successfully ran, so remove message recipes from the run list
                currentChefSolo.removeCookbook(cookbook);
                JsonUtil.FULL_MAPPER.writeValue(new File(chefStaging, SOLO_JSON), currentChefSolo);
                break;

            case SYNCHRONIZE:
                runChefSolo(getChefDirFile(), soloJson, chefMessage);
                break;

            default:
                throw new IllegalArgumentException("Invalid chefMessage (neither add nor remove): "+chefMessage);
        }
    }

    protected boolean useSudo () { return true; }

    protected void runChefSolo(File chefDir, String script, String cookbook, ChefMessage chefMessage) throws Exception {
        final CommandLine chefSoloCommand = new CommandLine("sudo")
                .addArgument("bash")
                .addArgument(script+".sh")
                .addArgument(cookbook);

        final Command chefCommand = new Command(chefSoloCommand)
                .setCopyToStandard(true)
                .setDir(chefDir)
                .setOut(getChefProgressFilter(new ChefSolo(cookbook, chefDir), chefMessage));

        final CommandResult result;
        try {
            result = CommandShell.exec(chefCommand);
            if (result.hasException()) throw result.getException();
            if (!result.isZeroExitStatus()) die("chef-solo exited with non-zero value: " + result.getExitStatus());
        } finally {
            log.info("chef run completed");
        }
    }

    protected void runChefSolo(File chefDir, File runlist, ChefMessage chefMessage) throws Exception {
        CommandLine chefSoloCommand = new CommandLine("sudo")
                .addArgument("bash")
                .addArgument("install.sh");
        final ChefSolo soloRunList;
        if (runlist != null) {
            chefSoloCommand = chefSoloCommand.addArgument(abs(runlist));
            soloRunList = fromJson(runlist, ChefSolo.class);
        } else {
            soloRunList = fromJson(new File(chefDir, SOLO_JSON), ChefSolo.class);
        }

        final Command chefCommand = new Command(chefSoloCommand)
                .setCopyToStandard(true)
                .setDir(chefDir)
                .setOut(getChefProgressFilter(soloRunList, chefMessage));

        final CommandResult result;
        try {
            result = CommandShell.exec(chefCommand);
            if (result.hasException()) throw result.getException();
            if (!result.isZeroExitStatus()) die("chef-solo exited with non-zero value: " + result.getExitStatus());
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
        mkdirOrDie(to);
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
