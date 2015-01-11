package rooty.toots.restore;

import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.string.Base64;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.cobbzilla.util.string.StringUtil.UTF8cs;

public class RestoreHandler extends RootyHandlerBase {

    @Override public boolean accepts(RootyMessage message) {
        return (message instanceof RestoreMessage) || (message instanceof GetRestoreKeyMessage);
    }

    @Override
    public boolean process(RootyMessage message) {

        if (message instanceof GetRestoreKeyMessage) {
            try {
                message.setResults(FileUtil.toString("/etc/.cloudos"));
            } catch (IOException e) {
                throw new IllegalStateException("Error reading backup key file: "+e, e);
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
            env.put("RESTORE_LOG", restoreLog.getAbsolutePath());
            env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");

            CommandShell.execScript("$(sudo -u $(cat /etc/chef-user) bash -c 'cd && pwd')/restore.sh 2>&1 > " + restoreLog.getAbsolutePath(), env);
            return true;

        } catch (Exception e) {
            throw new IllegalStateException("Error restoring: " + e, e);
        }
    }
}
