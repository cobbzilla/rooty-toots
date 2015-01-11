package rooty.toots.restore;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import rooty.RootyMessage;

@Accessors(chain=true) @Slf4j
public class RestoreMessage extends RootyMessage {

    @Getter @Setter private String restoreKey;
    @Getter @Setter private String restoreDatestamp;
    @Getter @Setter private String notifyEmail;

}
