package rooty.toots.djbdns;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor @AllArgsConstructor
public class RemoveAllDnsMessage extends DnsMessage {

    @Getter @Setter private String domain;


}
