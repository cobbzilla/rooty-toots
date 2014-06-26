package rooty.toots.service;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Accessors(chain=true)
public class ServiceKeyVendorMessage {

    @Getter @Setter private String host;
    @Getter @Setter private String key;

}
