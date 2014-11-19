package rooty.toots.service;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.http.*;
import org.cobbzilla.util.io.DirFilter;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.util.security.ShaUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyMessage;
import rooty.toots.chef.AbstractChefHandler;
import rooty.toots.vendor.VendorSettingDisplayValue;
import rooty.toots.vendor.VendorSettingHandler;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.cobbzilla.util.json.JsonUtil.toJson;
import static org.cobbzilla.util.string.StringUtil.empty;

@Slf4j
public class ServiceKeyHandler extends AbstractChefHandler {

    public static final String KEYNAME_PREFIX = "servicekey_";
    public static final String KEYNAME_SUFFIX = "_dsa";

    @Getter @Setter private String serviceDir = "/etc/ssl/service";
    @Getter @Setter private String serviceKeyEndpoint;

    @Getter @Setter private String sslKeysDir;
    @Getter @Setter private String defaultSslKeySha;

    private FileFilter defaultSslKeyFilter = new FileFilter() {
        @Override public boolean accept(File f) {
            try {
                return ShaUtil.sha256_file(f).equals(defaultSslKeySha);
            } catch (Exception e) {
                throw new IllegalStateException("Error computing shasum: "+e);
            }
        }
    };

    @Getter(lazy=true) private final String keyDir = initKeyDir();
    public String initKeyDir() {
        final File keyDir = new File(getServiceDir());
        if (!keyDir.exists() && !keyDir.mkdirs()) {
            throw new IllegalStateException("Error creating /etc/ssl/service");
        }
        return keyDir.getAbsolutePath();
    }


    @Override public boolean accepts(RootyMessage message) { return message instanceof ServiceKeyRequest; }

    public static String keyname(String name) { return KEYNAME_PREFIX + name + KEYNAME_SUFFIX; }

    @Override
    public void process(RootyMessage message) {

        if (message instanceof ServiceKeyRequest) {
            processServiceKey((ServiceKeyRequest) message);

        } else {
            throw new IllegalArgumentException("Invalid message type: "+message.getClass().getName());
        }
    }

    private void processServiceKey(ServiceKeyRequest request) {
        final String keyname = keyname(request.getName());

        switch (request.getOperation()) {
            case ALLOW_SSH:
                try {
                    boolean allow = true;
                    if (vendorKeyExists()) {
                        allow = false;
                    } else {
                        for (File databag : allDatabags()) {
                            final List<VendorSettingDisplayValue> values;
                            values = VendorSettingHandler.listVendorSettings(getChefDir(), databag.getParentFile().getName());
                            for (VendorSettingDisplayValue value : values) {
                                if (value.getValue().equals(VendorSettingHandler.VENDOR_DEFAULT)) {
                                    allow = false;
                                    break;
                                }
                            }
                            if (!allow) break;
                        }
                    }
                    request.setResults(String.valueOf(allow));

                } catch (Exception e) {
                    request.setResults("ERROR: "+e);
                }
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

    private List<File> allDatabags() {
        final List<File> databags = new ArrayList<>();
        for (File dir : new File(getChefDir(), "data_bags").listFiles(DirFilter.instance)) {
            databags.addAll(Arrays.asList(dir.listFiles(JsonUtil.JSON_FILES)));
        }
        return databags;
    }

    public boolean vendorKeyExists() {
        return new File(sslKeysDir).listFiles(defaultSslKeyFilter).length > 0;
    }

    public void sendVendorMessage(ServiceKeyRequest request) {
        if (empty(serviceKeyEndpoint)) throw new IllegalStateException("sendVendorMessage: No serviceKeyEndpoint defined");
        try {
            final String privateKey = FileUtil.toString(getKeyDir() + "/" + keyname(request.getName()));
            final ServiceKeyVendorMessage vendorMessage = new ServiceKeyVendorMessage()
                    .setKey(privateKey)
                    .setHost(CommandShell.hostname());

            final HttpRequestBean<String> requestBean = new HttpRequestBean<>(HttpMethods.POST, serviceKeyEndpoint, toJson(vendorMessage))
                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
            final HttpResponseBean response = HttpUtil.getResponse(requestBean);
            log.info("sendVendorMessage: returned "+response);
            if (response.getStatus() != HttpStatusCodes.OK) {
                throw new IllegalStateException("endpoint did not return 200: "+response);
            }

        } catch (Exception e) {
            throw new IllegalStateException("Error sending key to serviceKeyEndpoint ("+ serviceKeyEndpoint +"): "+e, e);
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
