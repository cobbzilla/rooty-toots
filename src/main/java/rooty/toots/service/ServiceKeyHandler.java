package rooty.toots.service;

import com.jayway.jsonpath.JsonPath;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.http.*;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.util.reflect.ReflectionUtil;
import org.cobbzilla.util.security.ShaUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileFilter;
import java.util.Map;

import static org.cobbzilla.util.json.JsonUtil.toJson;

@Slf4j
public class ServiceKeyHandler extends RootyHandlerBase {

    public static final String KEYNAME_PREFIX = "servicekey_";
    public static final String KEYNAME_SUFFIX = "_dsa";

    @Getter @Setter private String serviceDir = "/etc/ssl/service";
    @Getter @Setter private String vendorEndpoint;

    @Getter @Setter private String sslKeysDir;
    @Getter @Setter private String defaultSslKeySha;
    @Getter @Setter private VendorSettings vendor;

    @Getter(value=AccessLevel.PROTECTED, lazy=true) private final String chefUser = initChefUser();

    protected String initChefUser() { return FileUtil.toStringOrDie("/etc/chef-user").trim(); }

    private FileFilter defaultSslKeyFilter = new FileFilter() {
        @Override public boolean accept(File f) {
            try {
                return ShaUtil.sha256_file(f).equals(defaultSslKeySha);
            } catch (Exception e) {
                throw new IllegalStateException("Error computing shasum: "+e);
            }
        }
    };

    @Getter(value=AccessLevel.PROTECTED, lazy=true) private final String chefUserHome = initChefUserHome();
    private String initChefUserHome() {
        try {
            return CommandShell.toString("echo ~" + getChefUser()).trim();
        } catch (Exception e) {
            throw new IllegalStateException("Error determining home directory: "+e, e);
        }
    }

    @Getter(lazy=true) private final String keyDir = initKeyDir();
    public String initKeyDir() {
        final File keyDir = new File(getServiceDir());
        if (!keyDir.exists() && !keyDir.mkdirs()) {
            throw new IllegalStateException("Error creating /etc/ssl/service");
        }
        return keyDir.getAbsolutePath();
    }


    @Override
    public boolean accepts(RootyMessage message) {
        return message instanceof ServiceKeyRequest || message instanceof VendorSettingRequest;
    }

    public static String keyname(String name) { return KEYNAME_PREFIX + name + KEYNAME_SUFFIX; }

    @Override
    public void process(RootyMessage message) {

        if (message instanceof VendorSettingRequest) {
            processVendorSetting((VendorSettingRequest) message);
            message.setResults(Boolean.TRUE.toString());

        } else if (message instanceof ServiceKeyRequest) {
            processServiceKey((ServiceKeyRequest) message);

        } else {
            throw new IllegalArgumentException("Invalid message type: "+message.getClass().getName());
        }
    }

    private void processVendorSetting(VendorSettingRequest request) {

        final String exportName = request.getName();
        final String newValue = request.getValue();

        final VendorSetting setting = vendor.getSetting(exportName);
        if (setting == null) throw new IllegalArgumentException("Invalid setting: "+ exportName);

        // update environment exports
        String value;
        try {
            final Map<String, String> exports = CommandShell.loadShellExports(vendor.getExports());
            value = exports.get(setting.getExport());
            if (value != null && value.equals(newValue)) {
                log.info("Setting unchanged ("+ exportName +"), not editing exports ("+vendor.getExports()+")");
                return;

            } else {
                CommandShell.replaceShellExport(vendor.getExports(), exportName, newValue);
            }

        } catch (Exception e) {
            throw new IllegalStateException("Error comparing key shasums: "+e, e);
        }

        // update chef databag
        try {
            final Object databag = JsonUtil.fromJson(FileUtil.toString(vendor.getDatabag()), vendor.getDbclass());
            ReflectionUtil.set(databag, setting.getJsonPath(), newValue);
            FileUtil.toFile(vendor.getDatabag(), JsonUtil.toJson(databag));

        } catch (Exception e) {
            throw new IllegalArgumentException("Error parsing/reading databag ("+vendor.getDatabag()+"): "+e, e);
        }
    }

    private void processServiceKey(ServiceKeyRequest request) {
        final String keyname = keyname(request.getName());

        switch (request.getOperation()) {
            case ALLOW_SSH:
                boolean allow = true;
                if (vendorKeyExists()) {
                    allow = false;
                } else {
                    for (VendorSetting setting : vendor.getSettings()) {
                        if (isDefaultSetting(setting)) {
                            allow = false;
                            break;
                        }
                    }
                }
                request.setResults(String.valueOf(allow));
                break;

            case GENERATE:
                switch (request.getRecipient()) {
                    case VENDOR:
                        generateKey(keyname);
                        sendVendorMessage(request);
                        break;

                    case CUSTOMER:
                        // ensure that the default vendor SSL key is not present
                        if (vendorKeyExists()) {
                            throw new IllegalStateException("Cannot send key to customer while default key is still installed");
                        }

                        // Add key to message result, it will be encrypted and put into memcached
                        // client can read it back from there to display to the end user.
                        generateKey(keyname);
                        request.setResults(FileUtil.toStringOrDie(new File(getKeyDir(), keyname)));
                }
                break;

            case DESTROY:
                destroyKey(keyname);
                break;
        }
    }

    private boolean isDefaultSetting(VendorSetting setting) {
        // check environment exports
        String key;
        try {
            final Map<String, String> exports = CommandShell.loadShellExports(vendor.getExports());
            key = exports.get(setting.getExport());
            if (ShaUtil.sha256_hex(key).equals(setting.getSha())) return true;

        } catch (Exception e) {
            throw new IllegalStateException("Error comparing key shasums: "+e, e);
        }

        // check chef databag
        try {
            key = JsonPath.read(vendor.getDatabag(), "$."+setting.getJsonPath());
            if (ShaUtil.sha256_hex(key).equals(setting.getSha())) return true;

        } catch (Exception e) {
            throw new IllegalArgumentException("Error parsing/reading databag ("+vendor.getDatabag()+"): "+e, e);
        }

        return false;
    }

    public boolean vendorKeyExists() {
        return new File(sslKeysDir).listFiles(defaultSslKeyFilter).length > 0;
    }

    public void sendVendorMessage(ServiceKeyRequest request) {
        try {
            final String privateKey = FileUtil.toString(getKeyDir() + "/" + keyname(request.getName()));
            final ServiceKeyVendorMessage vendorMessage = new ServiceKeyVendorMessage()
                    .setKey(privateKey)
                    .setHost(CommandShell.hostname());

            final HttpRequestBean<String> requestBean = new HttpRequestBean<>(HttpMethods.POST, vendorEndpoint, toJson(vendorMessage))
                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
            final HttpResponseBean response = HttpUtil.getResponse(requestBean);
            log.info("sendVendorMessage: returned "+response);
            if (response.getStatus() != HttpStatusCodes.OK) {
                throw new IllegalStateException("endpoint did not return 200: "+response);
            }

        } catch (Exception e) {
            throw new IllegalStateException("Error sending key to vendorEndpoint ("+vendorEndpoint+"): "+e, e);
        }
    }

    private static final String SSH_AUTH_FILE = "/.ssh/authorized_keys2";

    private String authfile() { return getChefUserHome() + SSH_AUTH_FILE; }

    // todo: move these scripts elsewhere? install with chef?
    private static final String GENERATE_SCRIPT
            = "ssh-keygen -t dsa -C _keyname -N '' -f _keydir/_keyname && "
            + "touch _authfile && chown _chefuser _authfile && chmod 600 _authfile && "
            + "cat _keydir/_keyname.pub >> _authfile";

    private static final String DESTROY_SCRIPT
            = "temp=$(mktemp /tmp/destroy.XXXXXXX) || exit 1\n"
            + "touch ${temp} && chmod 600 ${temp} && "
            + "cat _authfile | grep -v _keyname > ${temp} && "
            + "mv ${temp} _authfile && chown _chefuser _authfile && chmod 600 _authfile && "
            + "rm -f _keydir/_authfile _keydir/_authfile.pub\n";

    public void generateKey(String keyname) { CommandShell.execScript(GENERATE_SCRIPT
            .replace("_authfile", authfile())
            .replace("_keydir", getKeyDir())
            .replace("_chefuser", getChefUser())
            .replace("_keyname", keyname));
    }

    private void destroyKey(String keyname) { CommandShell.execScript(DESTROY_SCRIPT
            .replace("_keydir", getKeyDir())
            .replace("_authfile", authfile())
            .replace("_chefuser", getChefUser())
            .replace("_keyname", keyname));
    }
}
