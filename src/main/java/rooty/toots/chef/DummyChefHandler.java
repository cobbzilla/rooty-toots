package rooty.toots.chef;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.File;

@AllArgsConstructor
public class DummyChefHandler extends ChefHandler {

    @Getter @Setter private File chefHome;
    @Getter @Setter private String chefUser;

    @Override public String getChefUserHome() { return chefHome.getAbsolutePath(); }

    @Override public String getChefDir() { return chefHome.getAbsolutePath(); }

    @Override protected String initChefUser() { return chefUser; }

    @Override protected void runChefSolo(File chefDir, File runlist, ChefMessage chefMessage) throws Exception { /* noop */ }

    @Override protected boolean useSudo() { return false; }

}
