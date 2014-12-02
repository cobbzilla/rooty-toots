package rooty.toots.vendor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import rooty.RootyMessage;

import java.util.List;

import static org.cobbzilla.util.string.StringUtil.empty;

@NoArgsConstructor @AllArgsConstructor @Accessors(chain=true)
public class VendorSettingRequest extends RootyMessage {

    public VendorSettingRequest (String cookbook) { this.cookbook = cookbook; }

    @Getter @Setter private String cookbook;
    @Getter @Setter private List<String> fields;

    public boolean hasCookbook() { return !empty(cookbook); }

}
