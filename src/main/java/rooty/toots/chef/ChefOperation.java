package rooty.toots.chef;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.cobbzilla.util.daemon.ZillaRuntime;

import static org.cobbzilla.util.daemon.ZillaRuntime.empty;

public enum ChefOperation {

    ADD, REMOVE;

    @JsonCreator public static ChefOperation create(String val) {
        return empty(val) ? null : ChefOperation.valueOf(val);
    }

}
