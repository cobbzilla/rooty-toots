package rooty.toots.app;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

public enum AppScriptMessageType {

    user_exists (new int[]{0, 1}), user_create, user_delete, user_change_password;

    @Getter private int[] exitValues = { 0 };

    AppScriptMessageType() {}

    AppScriptMessageType(int[] exitValues) { this.exitValues = exitValues; }

    @JsonCreator public static AppScriptMessageType create(String value) { return valueOf(value.toLowerCase()); }


}
