package rooty.toots.restore;

import lombok.Getter;
import lombok.Setter;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.string.Base64;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.cobbzilla.util.daemon.ZillaRuntime.die;
import static org.cobbzilla.util.io.FileUtil.abs;
import static org.cobbzilla.util.string.StringUtil.UTF8cs;

public class RestoreHandler extends RootyHandlerBase {

    @Getter @Setter private String restoreKeyFile;

    @Override public boolean accepts(RootyMessage message) {
        return (message instanceof RestoreMessage) || (message instanceof GetRestoreKeyMessage);
    }

    @Override
    public boolean process(RootyMessage message) {

        if (message instanceof GetRestoreKeyMessage) {
            try {
                message.setResults(FileUtil.toString(restoreKeyFile));
            } catch (IOException e) {
                return die("Error reading backup key file ("+restoreKeyFile+"): "+e, e);
            }
            return true;
        }

        final RestoreMessage restoreMessage = (RestoreMessage) message;

        try {
            final String envData = new String(Base64.decode(restoreMessage.getRestoreKey()), UTF8cs);
            final Map<String, String> env = CommandShell.loadShellExports(new ByteArrayInputStream(envData.getBytes()));
            final File restoreLog = File.createTempFile("restore", ".log");

            // remove all non-word chars from BACKUP_KEY (should not happen, but just in case)
            env.put("BACKUP_KEY", env.get("BACKUP_KEY").replaceAll("\\W", ""));

            env.put("RESTORE_NOTIFY_EMAIL", restoreMessage.getNotifyEmail());
            env.put("RESTORE_LOG", abs(restoreLog));
            env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");

            CommandShell.execScript("$(sudo -u $(cat /etc/chef-user) bash -c 'cd && pwd')/restore.sh 2>&1 > " + abs(restoreLog), env);
            return true;

        } catch (Exception e) {
            return die("Error restoring: " + e, e);
        }
    }
}
