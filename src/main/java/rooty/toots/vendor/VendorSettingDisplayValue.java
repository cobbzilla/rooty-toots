package rooty.toots.vendor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor @AllArgsConstructor
public class VendorSettingDisplayValue {

    public VendorSettingDisplayValue (String path, String value) {
        this.path = path;
        this.value = value;
    }

    @Getter @Setter private String path;
    @Getter @Setter private String value;
    @Getter @Setter private boolean readOnly = false;

}
