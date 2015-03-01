package rooty.toots.service;

import com.google.common.io.Files;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.io.StreamUtil;
import org.cobbzilla.util.json.JsonUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import rooty.mock.MockRootyStatusManager;
import rooty.toots.vendor.*;

import java.io.File;

import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VendorSettingsTest {

    public static final File TMP_NONEXISTENT_PATH = new File("/tmp/" + RandomStringUtils.randomAlphanumeric(10));

    private File databagFile;
    private File chefHome;
    private MockRootyStatusManager statusManager = new MockRootyStatusManager();

    private VendorSettingHandler handler = new VendorSettingHandler() {
        @Override public String getChefUserHome() { return chefHome.getAbsolutePath(); }
        @Override public String getChefDir() { return chefHome.getAbsolutePath(); }
        @Override protected String initChefUser() { return "nobody"; }
        @Override protected String getVendorKeyRootPaths() { return TMP_NONEXISTENT_PATH.getAbsolutePath(); }
    };
    private String cookbook;

    @Before public void setup () throws Exception {
        chefHome = Files.createTempDir();
        cookbook = randomAlphanumeric(10);
        databagFile = new File(chefHome.getAbsolutePath()+"/data_bags/"+cookbook+"/databag.json");
        if (!databagFile.getParentFile().mkdirs()) throw new IllegalStateException("error creating directories");
        FileUtil.toFile(databagFile, StreamUtil.loadResourceAsString("databag.json"));

        handler.setStatusManager(statusManager);
        if (!TMP_NONEXISTENT_PATH.exists() && !TMP_NONEXISTENT_PATH.mkdirs()) {
            throw new IllegalStateException("error creating dir: "+TMP_NONEXISTENT_PATH);
        }
    }

    @After public void teardown () throws Exception {
        if (TMP_NONEXISTENT_PATH.exists()) FileUtils.deleteDirectory(TMP_NONEXISTENT_PATH);
    }

    @Test public void testUpdateSetting () throws Exception {

        VendorSettingRequest updateRequest;
        VendorSettingDisplayValue[] values;

        // list the settings, one should be a default, the other should not
        final VendorSettingRequest listRequest = new VendorSettingsListRequest().setCookbook(cookbook);
        listRequest.initUuid();
        handler.process(listRequest);
        values = JsonUtil.fromJson(listRequest.getResults(), VendorSettingDisplayValue[].class);
        assertEquals(4, values.length);
        assertEquals("databag/foo", values[0].getPath());
        assertEquals(VendorSettingHandler.VENDOR_DEFAULT, values[0].getValue());
        assertEquals("databag/bar.quux.blah", values[1].getPath());
        assertEquals("bar", values[1].getValue());
        assertEquals("databag/bar.baz", values[2].getPath());
        assertEquals("zzz", values[2].getValue());
        assertEquals("databag/number", values[3].getPath());
        assertEquals(VendorSettingHandler.VENDOR_DEFAULT, values[3].getValue());

        // update foo and number
        final String fooValue = randomAlphanumeric(10);
        updateRequest = new VendorSettingUpdateRequest("databag/foo", fooValue).setCookbook(cookbook);
        updateRequest.initUuid();
        handler.process(updateRequest);
        assertTrue(Boolean.valueOf(updateRequest.getResults()));

        final int numValue = (int) (System.currentTimeMillis() % 3498345);
        updateRequest = new VendorSettingUpdateRequest("databag/number", String.valueOf(numValue)).setCookbook(cookbook);
        updateRequest.initUuid();
        handler.process(updateRequest);
        assertTrue(Boolean.valueOf(updateRequest.getResults()));

        // re-list settings, now we should see new values for foo and number
        listRequest.initUuid();
        handler.process(listRequest);
        values = JsonUtil.fromJson(listRequest.getResults(), VendorSettingDisplayValue[].class);
        assertEquals(4, values.length);
        assertEquals("databag/foo", values[0].getPath());
        assertEquals(fooValue, values[0].getValue());
        assertEquals("databag/bar.quux.blah", values[1].getPath());
        assertEquals("bar", values[1].getValue());
        assertEquals("databag/bar.baz", values[2].getPath());
        assertEquals("zzz", values[2].getValue());
        assertEquals("databag/number", values[3].getPath());
        assertEquals(String.valueOf(numValue), values[3].getValue());
    }

    public static class DummyDatabag {
        @Getter @Setter private int id;
        @Getter @Setter private String foo;
        @Getter @Setter private DummyDatabagBar bar;
        @Getter @Setter private VendorDatabag vendor;
    }

    public static class DummyDatabagBar {
        @Getter @Setter private String baz;
        @Getter @Setter private DummyDatabagQuux quux;
    }

    public static class DummyDatabagQuux {
        @Getter @Setter private String blah;
    }
}
