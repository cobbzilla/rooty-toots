package rooty.toots.chef;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.cobbzilla.util.string.StringUtil;

public enum ChefOperation {

    ADD, REMOVE;

    @JsonCreator public static ChefOperation create(String val) {
        return StringUtil.empty(val) ? null : ChefOperation.valueOf(val);
    }

}
