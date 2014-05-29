package rooty.toots.djbdns;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rooty.RootyMessage;

@NoArgsConstructor @AllArgsConstructor
public class DnsMessage extends RootyMessage {

    @Getter @Setter private String line;
    public DnsMessage withLine (String s) { line = s; return this; }

}
