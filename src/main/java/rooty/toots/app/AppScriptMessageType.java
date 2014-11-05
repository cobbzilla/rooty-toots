package rooty.toots.app;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AppScriptMessageType {

    user_exists, user_create, user_delete, user_change_password;

    @JsonCreator public static AppScriptMessageType create(String value) { return valueOf(value.toLowerCase()); }

}
