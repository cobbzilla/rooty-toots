package rooty.toots.djbdns;

import lombok.NoArgsConstructor;
import org.cobbzilla.util.dns.DnsRecordMatch;

@NoArgsConstructor
public class ListDnsMessage extends DnsMatchMessage {

    public ListDnsMessage(DnsRecordMatch match) { super(match); }

}
