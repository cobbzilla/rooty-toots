package rooty.toots.djbdns;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.cobbzilla.util.dns.DnsManager;
import org.cobbzilla.util.dns.DnsRecord;
import org.cobbzilla.util.dns.DnsRecordMatch;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.string.StringUtil;
import org.cobbzilla.util.system.Command;
import org.cobbzilla.util.system.CommandShell;
import org.cobbzilla.util.system.Sleep;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.cobbzilla.util.daemon.ZillaRuntime.die;
import static org.cobbzilla.util.daemon.ZillaRuntime.empty;
import static org.cobbzilla.util.json.JsonUtil.fromJsonOrDie;
import static org.cobbzilla.util.json.JsonUtil.toJson;
import static org.cobbzilla.util.system.CommandShell.exec;

@Slf4j
public class DnsHandler extends RootyHandlerBase implements DnsManager {

    public static final CommandLine MAKE = new CommandLine("make");
    public static final File ETC_HOSTS = new File("/etc/hosts");

    private static final DnsLineParser parser = new DnsLineParser();

    @Getter @Setter private String dataFile;
    @Getter @Setter private String serviceDir;
    @Getter @Setter private String svc;
    @Getter @Setter private String etcHosts;

    @Getter @Setter private String secret;

    private String getSvcCommand() { return svc == null ? "svc" : svc; }
    private File getEtcHostsFile() { return etcHosts == null ? ETC_HOSTS : new File(etcHosts); }

    @Override public boolean accepts(RootyMessage message) {
        return message instanceof DnsMessage || message instanceof DnsMatchMessage;
    }

    @Override public void publish() throws Exception {
        // for now, noop -- all changes are immediately published
    }

    @Override public int remove(DnsRecordMatch match) {
        return Integer.parseInt(request(new RemoveDnsMessage(match)).getResults());
    }

    @Override public List<DnsRecord> list(DnsRecordMatch match) {
        final RootyMessage result = request(new ListDnsMessage(match));
        if (empty(result.getResults())) return new ArrayList<>();
        return Arrays.asList(fromJsonOrDie(result.getResults(), DnsRecord[].class));
    }

    @Override public boolean write(DnsRecord record) throws Exception {

        String fqdn = record.getFqdn();
        if (!fqdn.endsWith(".")) fqdn += ".";

        String value = record.getValue();
        final StringBuilder line = new StringBuilder();

        switch (record.getType()) {
            case A:
                line.append("+").append(fqdn)
                        .append(":").append(value)
                        .append(":").append(record.getTtl());
                break;

            case CNAME:
                if (!value.endsWith(".")) value += ".";
                line.append("C").append(fqdn)
                        .append(":").append(value)
                        .append(":").append(record.getTtl());
                break;

            case MX:
                if (!value.endsWith(".")) value += ".";
                line.append("@").append(fqdn)
                        .append("::").append(value)
                        .append(":").append(record.getIntOption(DnsRecord.OPT_MX_RANK, 10))
                        .append(":").append(record.getTtl());
                break;

            case NS:
                if (!value.endsWith(".")) value += ".";
                line.append(".").append(fqdn)
                        .append(":").append(value)
                        .append(":").append(record.getOption(DnsRecord.OPT_NS_NAME))
                        .append(":").append(record.getTtl());
                break;

            case PTR:
                if (!value.endsWith(".")) value += ".";
                line.append("=").append(fqdn)
                        .append(":").append(value)
                        .append(":").append(record.getTtl());
                break;

            case TXT:
                line.append("'").append(fqdn)
                        .append(":").append(value)
                        .append(":").append(record.getTtl());
                break;

            default: throw new IllegalArgumentException("Unsupported record type: "+record.getType());
        }

        writeChange(line.toString());
        return true; // todo: detect whether we actually wrote a line
    }

    private void writeChange(String data) { getSender().write(new DnsMessage(data)); }

    @Override public synchronized boolean process(RootyMessage message) {

        if (message instanceof RemoveAllDnsMessage) {
            final RemoveAllDnsMessage msg = (RemoveAllDnsMessage) message;
            try {
                processRemoveAll(msg);
            } catch (IOException e) {
                die("Error removing from DNS: "+ msg.getDomain());
            }
            return true;
        }

        if (message instanceof ListDnsMessage) {
            final ListDnsMessage msg = (ListDnsMessage) message;
            try {
                processListRecords(msg);
            } catch (Exception e) {
                die("Error listing DNS records (query="+msg+"): "+e);
            }
            return true;
        }

        if (message instanceof RemoveDnsMessage) {
            final RemoveDnsMessage msg = (RemoveDnsMessage) message;
            try {
                processRemoveRecords(msg);
            } catch (Exception e) {
                die("Error listing DNS records (query="+msg+"): "+e);
            }
            return true;
        }

        final DnsMessage dnsMessage = (DnsMessage) message;

        final String[] parts = dnsMessage.getLine().split(":");
        final String id = parts[0] + ":";

        // read origData file
        final String origData;
        try {
            origData = FileUtil.toString(dataFile);
        } catch (IOException e) {
            return die("Error reading origData file: "+dataFile);
        }

        // todo: be smarter - many records can have the same first part (multiple mx records for example)
        // does the origData file already contain this record?
        if (origData.contains(id)) {
            log.info("origData file "+dataFile+" already contains record: "+id);

        } else {
            // add the record
            final String newData = origData + "\n" + dnsMessage.getLine();

            try {
                // write the new data file
                refreshDjbdns(newData);

            } catch (Exception e) {
                log.error("Error writing to origData file, trying to roll back: " + e);
                try {
                    writeDataFile(origData);
                } catch (IOException e1) {
                    die("Could read but not write to data file: " + dataFile + ": " + e1, e1);
                }
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                die("Error handling dnsMessage: " + dnsMessage.getLine() + ": " + e, e);
            }
        }

        // If this was an A record, add it to /etc/hosts too
        if (dnsMessage.getLine().startsWith("+")) {
            try {
                addToHostsFile(dnsMessage.getLine());
            } catch (IOException e) {
                die("Error adding to /etc/hosts: "+e, e);
            }
        }
        return true;
    }

    protected void refreshDjbdns(String newData) throws IOException {

        // write dataFile
        writeDataFile(newData);

        // run make
        File dataDir = new File(dataFile).getParentFile();
        exec(new Command(MAKE).setDir(dataDir));

        // restart tinydns
        final CommandLine restartTinydns = new CommandLine(getSvcCommand())
                .addArgument("-h").addArgument(serviceDir);
        exec(new Command(restartTinydns).setDir(dataDir));

        // todo: wait 5 seconds and check the output of svstat, ensure that uptime is increasing.
        // make sure it is not restarting constantly (that would be bad, we'd rollback)
    }

    private long lastWrite = System.currentTimeMillis();
    private void writeDataFile(String newData) throws IOException {

        // ensure more than 1 second elapses between writes: http://cr.yp.to/djbdns/axfrdns.html
        // "tinydns-data uses the modification time of the data file as its serial number for all zones.
        //  Do not make more than one modification per second."
        long sleepTime = 1100 - (System.currentTimeMillis() - lastWrite);
        if (sleepTime > 0) Sleep.sleep(sleepTime);

        FileUtil.toFile(dataFile, newData);
        lastWrite = System.currentTimeMillis();
    }

    private void processListRecords(ListDnsMessage msg) throws Exception {
        final List<DnsRecord> matches = new ArrayList<>();
        for (String line : FileUtil.toStringList(dataFile)) {
            final List<DnsRecord> parsed = parser.parseLine(line);
            if (parsed != null) {
                for (DnsRecord rec : parsed) {
                    if (rec.match(msg.getMatch())) matches.add(rec);
                }
            }
        }
        msg.setResults(toJson(matches));
    }

    private void processRemoveRecords(RemoveDnsMessage msg) throws Exception {
        final StringBuilder newData = new StringBuilder();
        int count = 0;
        for (String line : FileUtil.toStringList(dataFile)) {
            final List<DnsRecord> parsed = parser.parseLine(line);
            if (parsed != null && parsed.size() > 0) {
                // first record is the key one to match
                if (!parsed.get(0).match(msg.getMatch())) {
                    newData.append(line).append("\n");
                } else {
                    count++;
                }
            } else {
                newData.append(line).append("\n");
            }
        }
        refreshDjbdns(newData.toString());
        msg.setResults(String.valueOf(count));
    }

    private void processRemoveAll(RemoveAllDnsMessage dnsMessage) throws IOException {
        final String domain = dnsMessage.getDomain();
        final List<String> lines = new ArrayList<>();
        for (String line : FileUtil.toStringList(dataFile)) {
            if ( !( line.contains(domain+":") || line.contains(domain+".:") ) ) {
                lines.add(line);
            }
        }
        writeDataFile(StringUtil.toString(lines, "\n"));
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
            if (empty(hostLine) || hostLine.startsWith("#")) continue;
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
