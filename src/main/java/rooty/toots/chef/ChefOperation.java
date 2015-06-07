package rooty.toots.chef;

import com.fasterxml.jackson.annotation.JsonCreator;

import static org.cobbzilla.util.daemon.ZillaRuntime.empty;

public enum ChefOperation {

    ADD, REMOVE, SYNCHRONIZE;

    @JsonCreator public static ChefOperation create(String val) {
        return empty(val) ? null : ChefOperation.valueOf(val);
    }

}
