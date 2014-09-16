package rooty.toots.vendor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor @AllArgsConstructor
public class VendorSetting {

    @Getter @Setter private String path;
    @Getter @Setter private String sha;

    public VendorSetting (String path) { setPath(path); }

}
