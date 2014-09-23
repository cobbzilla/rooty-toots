package rooty.toots.chef;

import lombok.Getter;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;

public abstract class AbstractChefHandler extends RootyHandlerBase {

    @Getter(lazy=true) private final String chefUser = initChefUser();
    protected String initChefUser() { return FileUtil.toStringOrDie("/etc/chef-user").trim(); }

    @Getter(lazy=true) private final String chefUserHome = initChefUserHome();
    private String initChefUserHome() {
        try {
            return CommandShell.execScript("cd ~" + getChefUser() + " && pwd").trim();
        } catch (Exception e) {
            throw new IllegalStateException("Error determining home directory: "+e, e);
        }
    }

    @Getter(lazy=true) private final String chefDir = initChefDir();
    private String initChefDir() { return getChefUserHome() + "/chef"; }

}
