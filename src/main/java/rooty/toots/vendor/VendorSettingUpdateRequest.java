package rooty.toots.vendor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

@NoArgsConstructor @AllArgsConstructor @Accessors(chain=true)
public class VendorSettingUpdateRequest extends VendorSettingRequest {

    @Getter @Setter private VendorSetting setting;
    @Getter @Setter private String value;

    public VendorSettingUpdateRequest (String path, String value) {
        setting = new VendorSetting(path);
        this.value = value;
    }
}
