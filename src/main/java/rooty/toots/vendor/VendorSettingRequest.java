package rooty.toots.vendor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import rooty.RootyMessage;

import java.util.List;

@NoArgsConstructor @AllArgsConstructor @Accessors(chain=true)
public class VendorSettingRequest extends RootyMessage {

    @Getter @Setter private String cookbook;
    @Getter @Setter private List<String> fields;

}
