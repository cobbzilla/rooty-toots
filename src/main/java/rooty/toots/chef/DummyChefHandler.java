package rooty.toots.chef;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.File;

import static org.cobbzilla.util.io.FileUtil.abs;

@AllArgsConstructor
public class DummyChefHandler extends ChefHandler {

    @Getter @Setter private File chefHome;
    @Getter @Setter private String chefUser;

    @Override public String getChefUserHome() { return getChefDir(); }

    @Override public String getChefDir() { return abs(chefHome); }

    @Override protected String initChefUser() { return chefUser; }

    @Override protected void runChefSolo(File chefDir, String script, String cookbook, ChefMessage chefMessage) { /* noop */ }

    @Override protected void runChefSolo(File chefDir, File runlist, ChefMessage chefMessage) throws Exception { /* noop */ }

    @Override protected boolean useSudo() { return false; }

}
