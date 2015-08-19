package rooty.toots.djbdns;

import lombok.NoArgsConstructor;
import org.cobbzilla.util.dns.DnsRecordMatch;

@NoArgsConstructor
public class RemoveDnsMessage extends DnsMatchMessage {

    public RemoveDnsMessage(DnsRecordMatch match) { super(match); }

}
