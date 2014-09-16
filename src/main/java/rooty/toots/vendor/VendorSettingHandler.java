package rooty.toots.vendor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.io.DirFilter;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.util.security.ShaUtil;
import org.cobbzilla.util.string.StringUtil;
import rooty.RootyMessage;
import rooty.toots.chef.AbstractChefHandler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class VendorSettingHandler extends AbstractChefHandler {

    public static final String VENDOR_DEFAULT = "__VENDOR__DEFAULT__";

    @Override public boolean accepts(RootyMessage message) { return message instanceof VendorSettingRequest; }

    @Override
    public void process(RootyMessage message) {
        // should never happen
        if (!(message instanceof VendorSettingRequest)) {
            throw new IllegalArgumentException("Invalid message: "+message);
        }

        final VendorSettingRequest request = (VendorSettingRequest) message;

        if (request instanceof VendorSettingsListRequest) {
            try {
                request.setResults(listVendorSettings(request.getCookbook()));
            } catch (Exception e) {
                request.setError(e.getMessage());
                request.setResults("ERROR: "+e.getMessage());
            }

        } else if (message instanceof VendorSettingUpdateRequest) {
            try {
                updateVendorSetting((VendorSettingUpdateRequest) request);
                message.setResults(Boolean.TRUE.toString());
            } catch (Exception e) {
                request.setError(e.getMessage());
                request.setResults("ERROR: " + e.getMessage());
            }
        }
    }

    public String listVendorSettings(String cookbook) throws Exception {
        final String chefDir = getChefDir();
        if (StringUtil.empty(cookbook)) {
            final List<String> cookbooks = new ArrayList<>();
            for (File dir : new File(chefDir +"/data_bags").listFiles(DirFilter.instance)) {
                for (File databag : new File(chefDir+"/data_bags/"+cookbook).listFiles(JsonUtil.JSON_FILES)) {
                    final VendorDatabag vendor = getVendorDatabag(databag.getName(), FileUtil.toString(databag));
                    if (vendor == null) continue;
                    cookbooks.add(dir.getName());
                }
                cookbooks.add(dir.getName());
            }
            return JsonUtil.toJson(cookbooks);

        } else {
            return JsonUtil.toJson(listVendorSettings(chefDir, cookbook));
        }
    }

    public static List<VendorSettingDisplayValue> listVendorSettings(String chefDir, String cookbook) throws Exception {
        if (cookbook == null) throw new IllegalArgumentException("cookbook cannot be null");
        final List<VendorSettingDisplayValue> values = new ArrayList<>();
        for (File databag : new File(chefDir+"/data_bags/"+cookbook).listFiles(JsonUtil.JSON_FILES)) {
            final String databagJson = FileUtil.toString(databag);
            final VendorDatabag vendor = getVendorDatabag(databag.getName(), databagJson);
            if (vendor == null) continue;
            for (VendorDatabagSetting setting : vendor.getSettings()) {
                final VendorSettingDisplayValue value = new VendorSettingDisplayValue();
                value.setPath(setting.getPath());
                final String settingValue = JsonPath.read(databagJson, setting.getPath()).toString();
                if (setting.isBlock_ssh() && ShaUtil.sha256_hex(settingValue).equals(setting.getShasum())) {
                    value.setValue(VENDOR_DEFAULT);
                } else {
                    value.setValue(settingValue);
                }
                values.add(value);
            }
        }
        return values;
    }

    public static VendorDatabag getVendorDatabag(String databagName, String databagJson) throws Exception {
        try {
            return JsonUtil.fromJson(databagJson, "vendor", VendorDatabag.class);
        } catch (Exception e) {
            log.warn("Error getting vendor databag from: "+databagName+": "+e);
            return null;
        }
    }

    private void updateVendorSetting(VendorSettingUpdateRequest request) throws Exception {

        final String cookbook = request.getCookbook();
        if (cookbook == null) throw new IllegalArgumentException("no cookbook");

        final String path = request.getSetting().getPath();
        if (path == null) throw new IllegalArgumentException("no path");

        final String newValue = request.getValue();
        if (newValue == null) throw new IllegalArgumentException("no value");

        final DatabagSetting setting = getSetting(cookbook, path);
        if (setting == null) throw new IllegalArgumentException("Invalid setting: "+cookbook+"/"+path);

        // update databag
        try {
            final ObjectNode newDatabag = JsonUtil.replaceNode(setting.getDatabag(), path, newValue);
            FileUtil.toFile(setting.getDatabag(), JsonUtil.toJson(newDatabag));

        } catch (Exception e) {
            throw new IllegalArgumentException("Error updating setting: "+e, e);
        }
    }

    private DatabagSetting getSetting(String cookbook, String path) throws Exception {
        final String chefDir = getChefDir();
        final File databagDir = new File(chefDir + "/data_bags/" + cookbook);
        for (File databag : databagDir.listFiles(JsonUtil.JSON_FILES)) {
            final String databagJson = FileUtil.toString(databag);
            final VendorDatabag vendor = getVendorDatabag(databag.getName(), databagJson);
            for (VendorDatabagSetting setting : vendor.getSettings()) {
                if (setting.getPath().equals(path)) return new DatabagSetting(databag, setting);
            }
        }
        return null;
    }

    @AllArgsConstructor @Accessors(chain=true)
    private class DatabagSetting {
        @Getter @Setter private File databag;
        @Getter @Setter private VendorDatabagSetting setting;
    }
}
