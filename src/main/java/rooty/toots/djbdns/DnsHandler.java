package rooty.toots.djbdns;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.string.StringUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class DnsHandler extends RootyHandlerBase {

    public static final CommandLine MAKE = new CommandLine("make");
    public static final File ETC_HOSTS = new File("/etc/hosts");

    @Getter @Setter private String dataFile;
    @Getter @Setter private String serviceDir;
    @Getter @Setter private String svc;
    @Getter @Setter private String etcHosts;

    private String getSvcCommand() { return svc == null ? "svc" : svc; }
    private File getEtcHostsFile() { return etcHosts == null ? ETC_HOSTS : new File(etcHosts); }

    @Override public boolean accepts(RootyMessage message) { return message instanceof DnsMessage; }

    public void writeA(String secret, String hostname, String ip, int ttl) {
        final String data = new StringBuilder().append("+").append(hostname).append(":").append(ip).append(":").append(ttl).toString();
        writeChange(secret, data);
    }

    public void writeMX(String secret, String mailDomain, String mxHostname, int rank, int ttl) {
        final String data = new StringBuilder().append("@").append(mailDomain).append(".::").append(mxHostname).append(":").append(ttl).toString();
        writeChange(secret, data);
    }

    public void delegate(String secret, String fqdn, String ip, int ttl) {
        final String data = new StringBuilder().append(".").append(fqdn).append(".:").append(ip).append(":a:").append(ttl).toString();
        writeChange(secret, data);
    }

    public void writeNS(String secret, String fqdn, String ip, int ttl) {
        final String data = new StringBuilder().append("&").append(fqdn).append(".:").append(ip).append(":a:").append(ttl).toString();
        writeChange(secret, data);
    }

    public void removeAll(String secret, String domain) {
        write(new RemoveAllDnsMessage(domain), secret);
    }

    private void writeChange(String secret, String data) {
        write(new DnsMessage(data), secret);
    }

    @Override
    public synchronized void process(RootyMessage message) {

        if (message instanceof RemoveAllDnsMessage) {
            final RemoveAllDnsMessage msg = (RemoveAllDnsMessage) message;
            try {
                processRemoveAll(msg);
            } catch (IOException e) {
                throw new IllegalStateException("Error removing from DNS: "+ msg.getDomain());
            }
            return;
        }

        final DnsMessage dnsMessage = (DnsMessage) message;

        final File dataDir = new File(dataFile).getParentFile();

        final String[] parts = dnsMessage.getLine().split(":");
        final String id = parts[0] + ":";

        // read origData file
        final String origData;
        try {
            origData = FileUtil.toString(dataFile);
        } catch (IOException e) {
            throw new IllegalStateException("Error reading origData file: "+dataFile);
        }

        // does the origData file already contain this record?
        if (origData.contains(id)) {
            log.info("origData file "+dataFile+" already contains record: "+id);

        } else {
            // add the record
            final String newData = origData + "\n" + dnsMessage.getLine();

            try {
                // write the new data file
                FileUtil.toFile(dataFile, newData);

                // run make
                CommandShell.exec(MAKE, dataDir);

                // restart tinydns
                final CommandLine restartTinydns = new CommandLine(getSvcCommand()).addArgument("-h").addArgument(serviceDir);
                CommandShell.exec(restartTinydns, dataDir);

                // todo: wait 5 seconds and check the output of svstat, ensure that uptime is increasing.
                // make sure it is not restarting constantly (that would be bad, we'd rollback)

            } catch (Exception e) {
                log.error("Error writing to origData file, trying to roll back: " + e);
                try {
                    FileUtil.toFile(dataFile, origData);
                } catch (IOException e1) {
                    throw new IllegalStateException("Could read but not write to data file: " + dataFile + ": " + e1, e1);
                }
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new IllegalStateException("Error handling dnsMessage: " + dnsMessage.getLine() + ": " + e, e);
            }
        }

        // If this was an A record, add it to /etc/hosts too
        if (dnsMessage.getLine().startsWith("+")) {
            try {
                addToHostsFile(dnsMessage.getLine());
            } catch (IOException e) {
                throw new IllegalStateException("Error adding to /etc/hosts: "+e, e);
            }
        }

    }

    private void processRemoveAll(RemoveAllDnsMessage dnsMessage) throws IOException {
        final String domain = dnsMessage.getDomain();
        final List<String> lines = new ArrayList<>();
        for (String line : FileUtil.toStringList(dataFile)) {
            if ( !( line.contains(domain+":") || line.contains(domain+".:") ) ) {
                lines.add(line);
            }
        }
        FileUtil.toFile(dataFile, StringUtil.toString(lines, "\n"));
    }

    private void addToHostsFile(String line) throws IOException {

        final File hostsFile = getEtcHostsFile();

        String data = line.trim().substring(1);
        String[] parts = data.split(":");
        String ip = parts[1];
        String hostname = parts[0];
        String origData = FileUtil.toString(hostsFile);

        boolean exists = false;
        for (String hostLine : origData.split("\n")) {
            hostLine = hostLine.trim();
            if (StringUtil.empty(hostLine) || hostLine.startsWith("#")) continue;
            parts = hostLine.split("\\s+");
            if (parts.length < 2) continue; // weird, log this
            for (int i=1; i<parts.length; i++) {
                if (parts[i].equals(hostname)) {
                    log.info("hostname already defined, not redefining: "+hostname);
                    exists = true;
                    break;
                }
            }
            if (exists) break;
        }

        if (exists) return;

        // append to file
        try (Writer writer = new FileWriter(hostsFile, true)) {
            writer.write("\n" + ip + "  " + hostname + "\n");
        }

        // sanity -- ensure proper file permissions
        CommandShell.chmod(hostsFile, "644");
    }

}
