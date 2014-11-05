package rooty.toots.app;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import rooty.RootyMessage;

import java.util.ArrayList;
import java.util.List;

@Accessors(chain=true)
public class AppScriptMessage extends RootyMessage {

    @Getter @Setter private String app;
    @Getter @Setter private AppScriptMessageType type;
    @Getter @Setter private List<String> args = new ArrayList<>();

    public AppScriptMessage addArg (String arg) { args.add(arg); return this; }

    public boolean isApp(String app) { return this.app.equalsIgnoreCase(app); }

}
