package rooty.toots.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import rooty.RootyMessage;

@NoArgsConstructor @AllArgsConstructor
public class VendorSettingRequest extends RootyMessage {

    @Getter @Setter private String name;
    @Getter @Setter private String value;

}
