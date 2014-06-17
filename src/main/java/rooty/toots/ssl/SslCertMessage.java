package rooty.toots.ssl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.cobbzilla.util.string.StringUtil;
import rooty.RootyMessage;

@NoArgsConstructor @AllArgsConstructor
public class SslCertMessage extends RootyMessage {

    @Getter @Setter private String name;
    public boolean hasName() { return !StringUtil.empty(name); }
    public SslCertMessage withName (String n) { this.name = n; return this; }

}
