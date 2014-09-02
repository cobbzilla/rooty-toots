package rooty.toots.service;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class VendorSettings {

    @Getter @Setter private File exports;
    @Getter @Setter private File databag;
    @Getter @Setter private String databagClass;
    @Getter @Setter private VendorSetting[] settings;

    @Getter(lazy=true, value=AccessLevel.PROTECTED) private final Map<String, VendorSetting> settingsMap = initSettingsMap();
    private Map<String, VendorSetting> initSettingsMap() {
        if (settings == null || settings.length == 0) return Collections.emptyMap();
        final Map<String, VendorSetting> map = new HashMap<>(settings.length);
        for (VendorSetting s : settings) map.put(s.getExport(), s);
        return map;
    }

    public VendorSetting getSetting (String export) { return getSettingsMap().get(export); }

    @Getter(lazy=true, value=AccessLevel.PROTECTED) private final Class dbclass = initDbclass();
    private Class initDbclass() {
        try { return Class.forName(databagClass); } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Invalid databagClass: "+databagClass+": "+e, e);
        }
    }

}
