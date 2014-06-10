package rooty.toots.postfix;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.system.CommandShell;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;

@Slf4j
public class PostfixDigester {

    private static final String VMAILBOX_PREAMBLE
            = "root@HOSTNAME  HOSTNAME/postmaster/Maildir/\n"
            + "postmaster@HOSTNAME  HOSTNAME/postmaster/Maildir/\n";

    public static final CommandLine RESTART_POSTFIX = new CommandLine("service").addArgument("postfix").addArgument("restart");

    public static void digest(PostfixHandler handler) throws IOException {

        final File vmailboxFile = handler.getVmailboxFile();
        final String origData = FileUtil.toString(vmailboxFile);
        try {
            // write vmailbox file
            final String admin = handler.getAdmin();
            final String localDomain = handler.getLocalDomain();
            final Set<String> domains = handler.getDomains();

            try (Writer writer = new FileWriter(vmailboxFile)) {
                writer.write(VMAILBOX_PREAMBLE.replace("HOSTNAME", localDomain));
                for (String domain : domains) {
                    for (String user : handler.getUsers()) {
                        if (admin != null && user.equals(admin)) {
                            writer.write(user + "@" + domain + "  " + localDomain + "/postmaster/Maildir/\n");
                        } else {
                            writer.write(user + "@" + domain + "  " + localDomain + "/" + user + "/Maildir/\n");
                        }
                    }
                }
            }

            // update virtual_mailbox_domains in main.cf
            final File cfFile = new File(handler.getMainCf());
            final String mainCf = FileUtil.toString(cfFile);
            final StringBuilder b = new StringBuilder(mainCf.length());
            for (String line : mainCf.split("\n")) {
                if (line.trim().startsWith("virtual_mailbox_domains")) {
                    final StringBuilder b2 = new StringBuilder();
                    for (String domain : domains) {
                        if (b2.length() > 0) b2.append(", ");
                        b2.append(domain);
                    }
                    b.append("virtual_mailbox_domains = ").append(b2);

                } else {
                    b.append(line);
                }
                b.append("\n");
            }
            final String newConfig = b.toString();
            if (!newConfig.equals(mainCf)) FileUtil.toFile(cfFile, newConfig);

            CommandShell.exec(new CommandLine("postmap").addArgument(vmailboxFile.getAbsolutePath()));
            CommandShell.exec(new CommandLine("postmap").addArgument(handler.getVirtualFile().getAbsolutePath()));
            CommandShell.exec(RESTART_POSTFIX);

        } catch (Exception e) {
            log.error("Error applying new config, reverting to origData: "+e, e);
            try {
                FileUtil.toFile(vmailboxFile, origData);
                CommandShell.exec(new CommandLine("postmap").addArgument(vmailboxFile.getAbsolutePath()));
                CommandShell.exec(RESTART_POSTFIX);
                throw new IllegalStateException("Error applying new config, successfully reverted to origData. Problem was: "+e, e);

            } catch (Exception whoa) {
                throw new IllegalStateException("Error reverting: "+whoa, whoa);
            }
        }
    }

}
