package rooty.toots.system;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor @AllArgsConstructor
public class SystemSetTimezoneMessage extends SystemMessage {

    @Getter @Setter private String timezone;

}
