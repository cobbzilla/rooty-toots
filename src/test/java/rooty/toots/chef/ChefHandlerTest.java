package rooty.toots.chef;

import org.apache.commons.io.FileUtils;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.io.StreamUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.cobbzilla.util.io.FileUtil.abs;
import static org.cobbzilla.util.json.JsonUtil.fromJson;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static rooty.toots.chef.ChefSolo.SOLO_JSON;

public class ChefHandlerTest {

    private static final String CHEF_USER = System.getProperty("user.name");

    private File chefHome;
    private File chefMessageDir;
    private ChefHandler handler;

    public static final String[] CHEF_REPO_FILES = {
            SOLO_JSON,
            "cookbooks/app1/recipes/lib.rb",
            "cookbooks/app1/recipes/default.rb",
            "cookbooks/app1/recipes/validate.rb",
            "cookbooks/app2/recipes/default.rb",
    };

    public static final String[] CHEF_MESSAGE_FILES = {
            "cookbooks/newapp/recipes/default.rb"
    };
    private File tempDir;

    @Before public void setUp () throws Exception {

        tempDir = FileUtil.createTempDir(getClass().getName());

        chefHome = new File(tempDir, "chef-solo");
        copyFiles(chefHome, CHEF_REPO_FILES, "chef-solo");

        chefMessageDir = new File(tempDir, "chef-message");
        copyFiles(chefMessageDir, CHEF_MESSAGE_FILES, "chef-message");

        handler = new DummyChefHandler(chefHome, CHEF_USER);
    }

    @After public void cleanUp () throws Exception { FileUtils.deleteQuietly(tempDir); }

    public void copyFiles(File dest, String[] resources, String prefix) throws IOException {
        for (String resource : resources) {
            final File file = new File(abs(dest) + "/" + resource);
            if (!file.getParentFile().exists()) {
                assertTrue(file.getParentFile().mkdirs());
            }
            FileUtil.toFile(file, StreamUtil.loadResourceAsStream(prefix + "/" + resource));
        }
    }

    @Test public void testAddRecipe () throws Exception {

        final ChefMessage message = new ChefMessage()
                .setOperation(ChefOperation.ADD)
                .setChefDir(abs(chefMessageDir))
                .setCookbook("newapp");

        handler.process(message);

        // all files from the message should have been added to the chef repo
        for (String path : CHEF_MESSAGE_FILES) {
            final File file = new File(abs(chefHome) + "/" + path);
            assertTrue(file.exists());
        }

        final File soloJson = new File(abs(chefHome) + "/solo.json");
        assertTrue(soloJson.exists());
        final ChefSolo chefSolo = fromJson(soloJson, ChefSolo.class);
        assertTrue(chefSolo.containsCookbook("newapp"));
    }

    @Test public void testRemoveRecipe () throws Exception {

        final ChefMessage message = new ChefMessage()
                .setOperation(ChefOperation.REMOVE)
                .setChefDir("/tmp")
                .setCookbook("app1");

        handler.process(message);

        final File soloJson = new File(abs(chefHome) + "/solo.json");
        assertTrue(soloJson.exists());
        final ChefSolo chefSolo = fromJson(soloJson, ChefSolo.class);
        assertFalse(chefSolo.containsCookbook("app1"));
        assertTrue(chefSolo.containsCookbook("app2"));
    }

}
