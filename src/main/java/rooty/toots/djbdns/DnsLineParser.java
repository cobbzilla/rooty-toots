package rooty.toots.djbdns;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.cobbzilla.util.dns.DnsRecord;
import org.cobbzilla.util.dns.DnsType;

import java.util.ArrayList;
import java.util.List;

import static org.cobbzilla.util.daemon.ZillaRuntime.empty;
import static org.cobbzilla.util.system.NetworkUtil.getInAddrArpa;

@Slf4j
public class DnsLineParser {

    protected String getFqdn(String part) {
        final String fqdn = part.substring(1);
        return (fqdn.endsWith(".")) ? StringUtils.chop(fqdn) : fqdn;
    }

    public List<DnsRecord> parseLine(String line) {

        // ignored lines
        if (line.trim().startsWith("#") || line.trim().startsWith("-")) return null;

        final String[] parts = line.split(":");
        if (parts.length == 0 || parts[0].length() <= 1) return null;

        final char djbType = parts[0].charAt(0);
        switch (djbType) {
            case '.': return parseDot(parts);
            case '&': return parseAmpersand(parts);
            case '=': return parseEquals(parts);
            case '+': return parsePlus(parts);
            case '@': return parseAt(parts);
            case '\'': return parseSingleQuote(parts);
            case '^': return parseCaret(parts);
            case 'C': return parseCname(parts);
            case 'Z': return parseSoa(parts);
            default:
                log.warn("Unrecognized or unsupported line type: "+line);
                return null;
        }
    }

    public List<DnsRecord> parseDot(String[] parts) {
        final List<DnsRecord> records = new ArrayList<>(3);
        final String fqdn = getFqdn(parts[0]);
        final String ip = parts.length > 1 ? parts[1] : null;
        final String x = parts.length > 2 ? parts[2] : null;
        final Integer ttl = parts.length > 3 ? Integer.valueOf(parts[3]) : 0;

        final String ns = x == null ? null : x.contains(".") ? x : x +".ns."+fqdn;

        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setType(DnsType.NS)
                .setFqdn(fqdn)
                .setValue(ns));
        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setOption(DnsRecord.OPT_SOA_RNAME, "hostmaster." + fqdn)
                .setType(DnsType.SOA)
                .setFqdn(fqdn)
                .setValue(ns));
        if (!empty(ip)) {
            records.add((DnsRecord) new DnsRecord()
                    .setTtl(ttl)
                    .setType(DnsType.A)
                    .setFqdn(ns)
                    .setValue(ip));
        }
        return records;
    }

    public List<DnsRecord> parseAmpersand(String[] parts) {
        final List<DnsRecord> records = new ArrayList<>(3);
        final String fqdn = getFqdn(parts[0]);
        final String ip = parts.length > 1 ? parts[1] : null;
        final String x = parts.length > 2 ? parts[2] : null;
        final Integer ttl = parts.length > 3 ? Integer.valueOf(parts[3]) : 0;

        final String ns = x == null ? null : x.contains(".") ? x : x +".ns."+fqdn;

        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setType(DnsType.NS)
                .setFqdn(fqdn)
                .setValue(ns));

        if (!empty(ip)) {
            records.add((DnsRecord) new DnsRecord()
                    .setTtl(ttl)
                    .setType(DnsType.A)
                    .setFqdn(ns)
                    .setValue(ip));
        }

        return records;
    }

    public List<DnsRecord> parseEquals(String[] parts) {
        final List<DnsRecord> records = new ArrayList<>(3);
        final String fqdn = getFqdn(parts[0]);
        final String ip = parts.length > 1 ? parts[1] : null;
        final Integer ttl = parts.length > 2 ? Integer.valueOf(parts[2]) : 0;

        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setType(DnsType.A)
                .setFqdn(fqdn)
                .setValue(ip));
        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setType(DnsType.PTR)
                .setFqdn(getInAddrArpa(ip))
                .setValue(fqdn));
        return records;
    }

    public List<DnsRecord> parsePlus (String[] parts) {
        final List<DnsRecord> records = new ArrayList<>(3);
        final String fqdn = getFqdn(parts[0]);
        final String ip = parts.length > 1 ? parts[1] : null;
        final Integer ttl = parts.length > 2 ? Integer.valueOf(parts[2]) : 0;
        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setType(DnsType.A)
                .setFqdn(fqdn)
                .setValue(ip));
        return records;
    }

    public List<DnsRecord> parseAt(String[] parts) {
        final List<DnsRecord> records = new ArrayList<>(3);
        final String fqdn = getFqdn(parts[0]);
        final String ip = parts.length > 1 ? parts[1] : null;
        final String x = parts.length > 2 ? parts[2] : null;
        final String dist = parts.length > 3 ? parts[3] : null;
        final Integer ttl = parts.length > 4 ? Integer.valueOf(parts[4]) : 0;

        final String mx = x == null ? null : x.contains(".") ? x : x +".mx."+fqdn;

        records.add((DnsRecord) new DnsRecord()
                .setOption(DnsRecord.OPT_MX_RANK, dist)
                .setTtl(ttl)
                .setType(DnsType.MX)
                .setFqdn(fqdn)
                .setValue(mx));
        if (ip != null) {
            records.add((DnsRecord) new DnsRecord()
                    .setTtl(ttl)
                    .setType(DnsType.A)
                    .setFqdn(mx)
                    .setValue(ip));
        }
        return records;
    }

    public List<DnsRecord> parseSingleQuote (String[] parts) {
        final List<DnsRecord> records = new ArrayList<>(3);
        final String fqdn = getFqdn(parts[0]);
        final String s = parts.length > 1 ? parts[1] : null;
        final Integer ttl = parts.length > 2 ? Integer.valueOf(parts[2]) : 0;
        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setType(DnsType.TXT)
                .setFqdn(fqdn)
                .setValue(s));
        return records;
    }

    public List<DnsRecord> parseCaret (String[] parts) {
        final List<DnsRecord> records = new ArrayList<>(3);
        final String fqdn = getFqdn(parts[0]);
        final String p = parts.length > 1 ? parts[1] : null;
        final Integer ttl = parts.length > 2 ? Integer.valueOf(parts[2]) : 0;
        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setType(DnsType.PTR)
                .setFqdn(p)
                .setValue(fqdn));
        return records;
    }

    public List<DnsRecord> parseCname (String[] parts) {
        final List<DnsRecord> records = new ArrayList<>(3);
        final String fqdn = getFqdn(parts[0]);
        final String p = parts.length > 1 ? parts[1] : null;
        final Integer ttl = parts.length > 2 ? Integer.valueOf(parts[2]) : 0;
        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setType(DnsType.CNAME)
                .setFqdn(fqdn)
                .setValue(p));
        return records;
    }

    public List<DnsRecord> parseSoa(String[] parts) {
        final List<DnsRecord> records = new ArrayList<>(3);
        final String fqdn = getFqdn(parts[0]);
        final String mname = parts.length > 1 ? parts[1] : null;
        final String rname = parts.length > 2 ? parts[2] : null;
        final String serial = parts.length > 3 ? parts[3] : null;
        final String refresh = parts.length > 4 ? parts[4] : null;
        final String retry = parts.length > 5 ? parts[5] : null;
        final String expire = parts.length > 6 ? parts[6] : null;
        final String minimum = parts.length > 7 ? parts[7] : null;
        final Integer ttl = parts.length > 8 ? Integer.valueOf(parts[8]) : 0;
        records.add((DnsRecord) new DnsRecord()
                .setTtl(ttl)
                .setOption(DnsRecord.OPT_SOA_RNAME, rname)
                .setOption(DnsRecord.OPT_SOA_SERIAL, serial)
                .setOption(DnsRecord.OPT_SOA_REFRESH, refresh)
                .setOption(DnsRecord.OPT_SOA_RETRY, retry)
                .setOption(DnsRecord.OPT_SOA_EXPIRE, expire)
                .setOption(DnsRecord.OPT_SOA_MINIMUM, minimum)
                .setType(DnsType.SOA)
                .setFqdn(fqdn)
                .setValue(mname));
        return records;
    }

}
