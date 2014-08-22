package rooty.toots.djbdns;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import rooty.RootyMessage;

@Accessors(chain=true)
@NoArgsConstructor @AllArgsConstructor
public class DnsMessage extends RootyMessage {

    @Getter @Setter private String line;

}
