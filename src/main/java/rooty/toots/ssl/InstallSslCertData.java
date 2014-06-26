package rooty.toots.ssl;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@EqualsAndHashCode(of={"pem", "key"})
@Accessors(chain=true)
public class InstallSslCertData {

    @Getter @Setter private String pem;
    @Getter @Setter private String key;

}
