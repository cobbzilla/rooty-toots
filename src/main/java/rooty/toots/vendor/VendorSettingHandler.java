package rooty.toots.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.io.DirFilter;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.util.security.ShaUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyMessage;
import rooty.toots.chef.AbstractChefHandler;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.cobbzilla.util.json.JsonUtil.*;
import static org.cobbzilla.util.string.StringUtil.empty;

@Slf4j
public class VendorSettingHandler extends AbstractChefHandler {

    public static final String VENDOR_DEFAULT = "__VENDOR__DEFAULT__";
    public static final String VALUE_NOT_SET = "__NO_VALUE_SET__";

    @Override public boolean accepts(RootyMessage message) { return message instanceof VendorSettingRequest; }

    @Override
    public boolean process(RootyMessage message) {
        // should never happen
        if (!(message instanceof VendorSettingRequest)) {
            throw new IllegalArgumentException("Invalid message: "+message);
        }

        final VendorSettingRequest request = (VendorSettingRequest) message;

        if (request instanceof VendorSettingsListRequest) {
            if (request.hasCookbook()) {
                try {
                    request.setResults(listVendorSettings(request.getCookbook(), request.getFields()));
                } catch (Exception e) {
                    request.setError(e.getMessage());
                    request.setResults("ERROR: " + e.getMessage());
                }
            } else {
                request.setResults(allCookbooks());
            }
            return true;

        } else if (message instanceof VendorSettingUpdateRequest) {
            try {
                updateVendorSetting((VendorSettingUpdateRequest) request);
                message.setResults(Boolean.TRUE.toString());
            } catch (Exception e) {
                request.setError(e.getMessage());
                request.setResults("ERROR: " + e.getMessage());
            }
            return true;

        } else {
            log.info("Unrecognized message: "+message.getClass().getName());
            return false;
        }
    }

    private String allCookbooks() {
        final Set<String> cookbooks = new HashSet<>();
        for (File f : allDatabags(getChefDir())) {
            cookbooks.add(f.getParentFile().getName());
        }
        return toJsonOrDie(cookbooks);
    }

    protected static List<File> allDatabags(String chefDir) {
        final List<File> databags = new ArrayList<>();
        for (File dir : new File(chefDir, "data_bags").listFiles(DirFilter.instance)) {
            databags.addAll(Arrays.asList(dir.listFiles(JsonUtil.JSON_FILES)));
        }
        return databags;
    }

    public String listVendorSettings(String cookbook, List<String> fields) throws Exception {

        if (empty(cookbook)) throw new IllegalArgumentException("No cookbook");

        final List<VendorSettingDisplayValue> values = new ArrayList<>();
        final Map<String, JsonNode> databagJson = new HashMap<>();
        final Map<String, VendorDatabag> vendorDatabags = new HashMap<>();

        if (fields == null || fields.isEmpty()) {
            fields = new ArrayList<>();
            final List<VendorSettingDisplayValue> vendorSettings = fetchAllSettings(getChefDir());
            for (VendorSettingDisplayValue s : vendorSettings) {
                fields.add(s.getPath());
            }
        }

        for (String field : fields) {
            final VendorSettingPath path = new VendorSettingPath(field);
            JsonNode json = databagJson.get(path.databag);
            if (json == null) {
                final File databag = databagFile(cookbook, path.databag);
                if (!databag.exists()) throw new IllegalArgumentException("databag does not exist: "+databag.getAbsolutePath());
                json = toJsonNode(databag);
                databagJson.put(path.databag, json);
            }

            VendorDatabag vendor = vendorDatabags.get(path.databag);
            if (vendor == null) {
                vendor = getVendorDatabag(path.databag, json);
                if (vendor == null) vendor = VendorDatabag.NULL;
                vendorDatabags.put(path.databag, vendor);
            }

            final String settingValue = JsonUtil.nodeValue(json, path.path);
            if (!empty(settingValue)) {
                final VendorSettingDisplayValue value = new VendorSettingDisplayValue();
                value.setPath(path.displayPath());

                if (!vendor.equals(VendorDatabag.NULL)) {
                    final VendorDatabagSetting setting = vendor.getSetting(path.path);
                    value.setValue(getDisplayValue(setting, settingValue));
                } else {
                    value.setValue(settingValue);
                }
                values.add(value);
            }
        }

        return toJson(values);
    }

    protected static String getDisplayValue(VendorDatabagSetting setting, String settingValue) {
        if (settingValue == null) return VALUE_NOT_SET;
        if (setting != null && shouldMaskDefaultValue(settingValue, setting)) {
            return VENDOR_DEFAULT;
        } else {
            return settingValue;
        }
    }

    public static List<VendorSettingDisplayValue> fetchAllSettings(String chefDir) throws Exception {
        final List<VendorSettingDisplayValue> values = new ArrayList<>();
        for (File databag : allDatabags(chefDir)) {
            values.addAll(fetchDatabagSettings(databag));
        }
        return values;
    }

    public static List<VendorSettingDisplayValue> fetchDatabagSettings(File databag) throws Exception {
        final String databagName = databag.getName().substring(0, databag.getName().lastIndexOf('.'));
        final List<VendorSettingDisplayValue> values = new ArrayList<>();
        final JsonNode node = toJsonNode(databag);
        final VendorDatabag vendor = getVendorDatabag(databagName, node);
        if (vendor != null) {
            for (VendorDatabagSetting setting : vendor.getSettings()) {
                final String settingValue = JsonUtil.nodeValue(node, setting.getPath());
                final String displayPath = VendorSettingPath.displayPath(databagName, setting.getPath());
                final VendorSettingDisplayValue displayValue = new VendorSettingDisplayValue();
                displayValue.setPath(displayPath);
                displayValue.setValue(getDisplayValue(setting, settingValue));
                values.add(displayValue);
            }
        }
        return values;
    }

    protected static boolean shouldMaskDefaultValue(String settingValue, VendorDatabagSetting setting) {
        return setting != null && setting.isBlock_ssh() && ShaUtil.sha256_hex(settingValue).equals(setting.getShasum());
    }

    private File databagFile(String cookbook, String databag) {
        return databagFile(getChefDir(), cookbook, databag);
    }

    protected static File databagFile(String chefDir, String cookbook, String databag) {
        return new File(chefDir +"/data_bags/"+cookbook+"/"+databag+".json");
    }

    protected static JsonNode toJsonNode(File databag) throws IOException {
        return FULL_MAPPER.readTree(FileUtil.toString(databag));
    }

    public static VendorDatabag getVendorDatabag(String databagName, JsonNode node) throws Exception {
        try {
            final JsonNode vendor = node.get("vendor");
            if (vendor == null) return null;
            return JsonUtil.FULL_MAPPER.convertValue(vendor, VendorDatabag.class);
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

        final VendorSettingPath settingPath = new VendorSettingPath(path);

        final String newValue = request.getValue();
        if (newValue == null) throw new IllegalArgumentException("no value");

        final DatabagSettingWithValue setting = getSetting(cookbook, settingPath.displayPath());
        if (setting == null) throw new IllegalArgumentException("Invalid setting: "+cookbook+"/"+settingPath.displayPath());

        // update databag
        try {
            final ObjectNode newDatabag = JsonUtil.replaceNode(setting.getDatabag(), settingPath.path, newValue);
            FileUtil.toFile(setting.getDatabag(), toJson(newDatabag));

            if (setting.hasValue()) {
                // Changing a vendor setting that is blocking access, make sure we change it *everywhere*
                Set<File> files = findFilesWithSetting(setting);
                for (File file : files) {
                    final ObjectNode updatedDatabag = JsonUtil.replaceNode(file, settingPath.path, newValue);
                    FileUtil.toFile(file, toJson(updatedDatabag));
                }
                files = findFilesWithSetting(setting);
                if (!files.isEmpty()) {
                    request.setError("Vendor default value for " + settingPath.path + " still exists in some files: " + files);
                }
                request.setResults(String.valueOf(files.isEmpty()));

            } else {
                request.setResults(String.valueOf(true));
            }

        } catch (Exception e) {
            throw new IllegalArgumentException("Error updating setting: "+e, e);
        }
    }

    private Set<File> findFilesWithSetting(DatabagSettingWithValue setting) {
        final String[] files = CommandShell.execScript("find /etc /home -type f -name \"*.json*\" -exec grep -l -- '\"" + setting.getValue() + "\"' {} \\;").split("\\s+");
        final Set<File> found = new HashSet<>(files.length);
        for (String file : files) {
            if (!empty(file)) found.add(new File(file));
        }
        return found;
    }

    private DatabagSettingWithValue getSetting(String cookbook, String field) throws Exception {
        final VendorSettingPath path = new VendorSettingPath(field);
        final File databagFile = databagFile(cookbook, path.databag);
        final JsonNode node = toJsonNode(databagFile);
        final VendorDatabag vendor = getVendorDatabag(path.databag, node);
        if (vendor != null) {
            final VendorDatabagSetting setting = vendor.getSetting(path.path);
            final String settingValue = JsonUtil.nodeValue(node, path.path);
            if (shouldMaskDefaultValue(settingValue, setting)) {
                return new DatabagSettingWithValue(databagFile, setting, settingValue);
            }
        }
        return new DatabagSettingWithValue(databagFile, null, null);
    }

    private static class VendorSettingPath {
        public String databag;
        public String path;
        public VendorSettingPath(String field) {
            if (empty(field)) throw new IllegalArgumentException("No field");
            int slashPos = field.indexOf('/');
            if (slashPos == -1 || slashPos >= field.length()-1) throw new IllegalArgumentException("Invalid field: "+field);
            databag = field.substring(0, slashPos);
            path = field.substring(slashPos+1);
        }

        public String displayPath() { return displayPath(databag, path); }

        public static String displayPath(String databagName, String path) { return databagName + "/" + path; }
    }

    @AllArgsConstructor @Accessors(chain=true)
    private class DatabagSetting {
        @Getter @Setter private File databag;
        @Getter @Setter private VendorDatabagSetting setting;
        public boolean hasSetting () { return setting != null; }
    }

    @Accessors(chain=true)
    private class DatabagSettingWithValue extends DatabagSetting {
        public DatabagSettingWithValue (File databag, VendorDatabagSetting setting, String value) {
            super(databag, setting);
            setValue(value);
        }
        @Getter @Setter private String value;
        public boolean hasValue () { return !empty(value); }
    }
}
