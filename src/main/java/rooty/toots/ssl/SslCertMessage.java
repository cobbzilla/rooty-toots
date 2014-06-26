package rooty.toots.ssl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cobbzilla.util.string.StringUtil;
import rooty.RootyMessage;

@NoArgsConstructor @AllArgsConstructor @Accessors(chain=true)
public class SslCertMessage extends RootyMessage {

    @Getter @Setter private String name;
    public boolean hasName() { return !StringUtil.empty(name); }

}
