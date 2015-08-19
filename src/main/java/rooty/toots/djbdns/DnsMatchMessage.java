package rooty.toots.djbdns;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.cobbzilla.util.dns.DnsRecordMatch;
import rooty.RootyMessage;

@NoArgsConstructor
public class DnsMatchMessage extends RootyMessage {

    @Getter @Setter private DnsRecordMatch match;

    public DnsMatchMessage(DnsRecordMatch match) { this.match = match; }

}
