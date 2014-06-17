package rooty.toots.ssl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor @AllArgsConstructor
public class InstallSslCertMessage extends SslCertMessage {

    @Getter @Setter private InstallSslCertData data;

}
