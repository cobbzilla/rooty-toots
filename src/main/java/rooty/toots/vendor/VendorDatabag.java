package rooty.toots.vendor;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Accessors(chain=true)
public class VendorDatabag {

    public static final VendorDatabag NULL = new VendorDatabag();

    @Getter @Setter private String service_key_endpoint;
    @Getter @Setter private String ssl_key_sha;
    @Getter @Setter private List<VendorDatabagSetting> settings = new ArrayList<>();

    public VendorDatabag addSetting (VendorDatabagSetting setting) { settings.add(setting); return this; }

    public VendorDatabagSetting getSetting(String path) {
        for (VendorDatabagSetting s : settings) {
            if (s.getPath().equals(path)) return s;
        }
        return null;
    }

    public boolean containsSetting (String path) { return getSetting(path) != null; }

}
