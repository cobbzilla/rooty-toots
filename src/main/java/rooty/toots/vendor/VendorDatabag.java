package rooty.toots.vendor;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Accessors(chain=true)
public class VendorDatabag {

    @Getter @Setter private String service_key_endpoint;
    @Getter @Setter private String ssl_key_sha;
    @Getter @Setter private List<VendorDatabagSetting> settings = new ArrayList<>();

    public VendorDatabag addSetting (VendorDatabagSetting setting) { settings.add(setting); return this; }

}
