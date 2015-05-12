package rooty.toots.postfix;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.string.StringUtil;
import org.cobbzilla.util.system.CommandShell;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.cobbzilla.util.daemon.ZillaRuntime.die;
import static org.cobbzilla.util.io.FileUtil.abs;

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

            // update virtual file with aliases -- same set of aliases for each domain (for now)
            try (Writer writer = new FileWriter(handler.getVirtualFile())) {
                for (String domain : handler.getDomains()) {
                    final Map<String, List<String>> aliases = handler.getAliases();
                    for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
                        final String alias = domain.equals(handler.getLocalDomain()) ? entry.getKey() : entry.getKey()+"@"+domain;
                        writer.write("\n" + alias + "    " + StringUtil.toString(entry.getValue(), ", "));
                    }
                }
            }

            CommandShell.exec(new CommandLine("postmap").addArgument(abs(vmailboxFile)));
            CommandShell.exec(new CommandLine("postmap").addArgument(abs(handler.getVirtualFile())));
            CommandShell.exec(RESTART_POSTFIX);

        } catch (Exception e) {
            log.error("Error applying new config, reverting to origData: "+e, e);
            try {
                FileUtil.toFile(vmailboxFile, origData);
                CommandShell.exec(new CommandLine("postmap").addArgument(abs(vmailboxFile)));
                CommandShell.exec(RESTART_POSTFIX);
                die("Error applying new config, successfully reverted to origData. Problem was: "+e, e);

            } catch (Exception whoa) {
                die("Error reverting: "+whoa, whoa);
            }
        }
    }

}
