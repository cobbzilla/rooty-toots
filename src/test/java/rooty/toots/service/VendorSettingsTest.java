package rooty.toots.service;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang.RandomStringUtils;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.io.StreamUtil;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.util.security.ShaUtil;
import org.cobbzilla.util.system.CommandShell;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VendorSettingsTest {

    public static File exportsFile;
    private File databagFile;

    @Before
    public void setup () throws Exception {

        exportsFile = File.createTempFile("exports", ".env");
        databagFile = File.createTempFile("databag", ".json");

        FileUtil.toFile(exportsFile, StreamUtil.loadResourceAsString("exports.env"));
        FileUtil.toFile(databagFile, StreamUtil.loadResourceAsString("databag.json"));
    }

    @Test
    public void testUpdateSetting () throws Exception {

        ServiceKeyHandler handler = new ServiceKeyHandler();
        VendorSettingRequest request;

        final VendorSettings vendor = new VendorSettings();
        vendor.setExports(exportsFile);
        vendor.setDatabag(databagFile);
        vendor.setDatabagClass(DummyDatabag.class.getName());
        vendor.setSettings(new VendorSetting[] {
                new VendorSetting("VAR_A", "foo", ShaUtil.sha256_hex("foo")),
                new VendorSetting("VAR_B", "bar.baz", ShaUtil.sha256_hex("zzz"))
        });

        handler.setVendor(vendor);

        long exportsMtime = exportsFile.lastModified();
        long databagMtime = databagFile.lastModified();

        // Update with a value that is the same -- should not change mtime on either file
        request = new VendorSettingRequest("VAR_A", "foo");
        handler.process(request);
        assertEquals(exportsMtime, exportsFile.lastModified());
        assertEquals(databagMtime, databagFile.lastModified());
        assertTrue(Boolean.valueOf(request.getResults()));

        // Update VAR_A (foo), verify changes
        final String newFoo = "foo_"+ RandomStringUtils.randomAlphanumeric(10);
        request = new VendorSettingRequest("VAR_A", newFoo);
        handler.process(request);
        assertEquals(newFoo, CommandShell.loadShellExports(exportsFile).get("VAR_A"));
        assertEquals(newFoo, JsonUtil.fromJson(FileUtil.toString(databagFile), DummyDatabag.class).getFoo());
        assertTrue(Boolean.valueOf(request.getResults()));

        // Update VAR_B (bar.baz), verify changes
        final String newBarbaz = "barbaz_"+ RandomStringUtils.randomAlphanumeric(10);
        request = new VendorSettingRequest("VAR_B", newBarbaz);
        handler.process(request);
        assertEquals(newBarbaz, CommandShell.loadShellExports(exportsFile).get("VAR_B"));
        assertEquals(newBarbaz, JsonUtil.fromJson(FileUtil.toString(databagFile), DummyDatabag.class).getBar().getBaz());
        assertTrue(Boolean.valueOf(request.getResults()));
    }

    public static class DummyDatabag {
        @Getter @Setter private int id;
        @Getter @Setter private String foo;
        @Getter @Setter private DummyDatabagBar bar;

    }

    public static class DummyDatabagBar {
        @Getter @Setter private String baz;
        @Getter @Setter private DummyDatabagQuux quux;
    }

    public static class DummyDatabagQuux {
        @Getter @Setter private String blah;
    }
}
