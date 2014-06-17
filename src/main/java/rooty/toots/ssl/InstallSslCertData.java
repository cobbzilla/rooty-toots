package rooty.toots.ssl;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@EqualsAndHashCode(of={"pem", "key"})
public class InstallSslCertData {

    @Getter @Setter private String pem;
    public InstallSslCertData withPem (String p) { pem = p; return this; }

    @Getter @Setter private String key;
    public InstallSslCertData withKey (String k) { key = k; return this; }

}
