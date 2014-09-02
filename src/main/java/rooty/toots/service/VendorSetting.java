package rooty.toots.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor @AllArgsConstructor
public class VendorSetting {

    @Getter @Setter private String export;
    @Getter @Setter private String jsonPath;
    @Getter @Setter private String sha;

}
