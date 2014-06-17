package rooty.toots.ssl;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class RemoveSslCertMessage extends SslCertMessage {

    public RemoveSslCertMessage (String name) {
        super(name);
    }

}
