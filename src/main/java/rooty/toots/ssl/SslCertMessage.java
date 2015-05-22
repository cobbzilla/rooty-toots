package rooty.toots.ssl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cobbzilla.util.daemon.ZillaRuntime;
import rooty.RootyMessage;

import static org.cobbzilla.util.daemon.ZillaRuntime.empty;

@NoArgsConstructor @AllArgsConstructor @Accessors(chain=true)
public class SslCertMessage extends RootyMessage {

    @Getter @Setter private String name;
    public boolean hasName() { return !empty(name); }

}
