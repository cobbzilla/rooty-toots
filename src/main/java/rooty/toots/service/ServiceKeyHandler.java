package rooty.toots.service;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.http.*;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.security.ShaUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyMessage;
import rooty.toots.chef.AbstractChefHandler;
import rooty.toots.ssl.SslCertHandler;
import rooty.toots.vendor.VendorSettingDisplayValue;
import rooty.toots.vendor.VendorSettingHandler;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileFilter;
import java.util.List;

import static org.cobbzilla.util.json.JsonUtil.toJson;
import static org.cobbzilla.util.string.StringUtil.empty;

@Slf4j
public class ServiceKeyHandler extends AbstractChefHandler {

    public static final String KEYNAME_PREFIX = "servicekey_";
    public static final String KEYNAME_SUFFIX = "_dsa";

    @Getter @Setter private String serviceDir = "/etc/ssl/service";
    @Getter @Setter private String serviceKeyEndpoint;

    @Getter @Setter private String sslKeysDir = SslCertHandler.DEFAULT_SSL_KEY_PATH;
    @Getter @Setter private String defaultSslKeySha;

    private FileFilter defaultSslKeyFilter = new FileFilter() {
        @Override public boolean accept(File f) {
            return !empty(defaultSslKeySha) && ShaUtil.sha256_file(f).equals(defaultSslKeySha);
        }
    };

    @Getter(lazy=true) private final String serviceKeyDir = initServiceKeyDir();
    public String initServiceKeyDir() {
        final File keyDir = new File(getServiceDir());
        if (!keyDir.exists() && !keyDir.mkdirs()) {
            throw new IllegalStateException("Error creating service dir: "+getServiceDir());
        }
        return keyDir.getAbsolutePath();
    }


    @Override public boolean accepts(RootyMessage message) { return message instanceof ServiceKeyRequest; }

    public static String keyName(String name) { return KEYNAME_PREFIX + name + KEYNAME_SUFFIX; }

    public static String baseKeyName (String keyname) {
        if (keyname.startsWith(KEYNAME_PREFIX)) keyname = keyname.substring(KEYNAME_PREFIX.length());
        if (keyname.endsWith(KEYNAME_SUFFIX)) keyname = keyname.substring(0, keyname.length()-KEYNAME_SUFFIX.length());
        return keyname;
    }

    @Override
    public boolean process(RootyMessage message) {

        if (message instanceof ServiceKeyRequest) {
            return processServiceKey((ServiceKeyRequest) message);

        } else {
            throw new IllegalArgumentException("Invalid message type: "+message.getClass().getName());
        }
    }

    private boolean processServiceKey(ServiceKeyRequest request) {
        final String keyname = keyName(request.getName());

        switch (request.getOperation()) {
            case ALLOW_SSH:
                try {
                    boolean allow = true;
                    if (vendorKeyExists()) {
                        allow = false;
                    } else {
                        final List<VendorSettingDisplayValue> values = VendorSettingHandler.fetchAllSettings(getChefDir());
                        for (VendorSettingDisplayValue value : values) {
                            if (value.getValue().equals(VendorSettingHandler.VENDOR_DEFAULT)) {
                                allow = false;
                                break;
                            }
                        }
                    }
                    request.setResults(String.valueOf(allow));

                } catch (Exception e) {
                    request.setError(e.toString());
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
                        request.setResults(FileUtil.toStringOrDie(new File(this.getServiceKeyDir(), keyname)));
                }
                break;

            case DESTROY:
                destroyKey(keyname);
                break;

            default:
                log.warn("Unrecognized operation: "+request.getOperation());
                break;
        }
        return true;
    }

    public boolean vendorKeyExists() {
        if (empty(sslKeysDir)) {
            log.error("No sslKeysDir specified!");
            return false;
        }
        final File[] files = new File(sslKeysDir).listFiles(defaultSslKeyFilter);
        if (files == null) {
            log.warn("vendorKeyExists: sslKeysDir might not exist: "+sslKeysDir);
            return false;
        }
        return files.length > 0;
    }

    public void sendVendorMessage(ServiceKeyRequest request) {
        if (empty(serviceKeyEndpoint)) throw new IllegalStateException("sendVendorMessage: No serviceKeyEndpoint defined");
        try {
            final String privateKey = FileUtil.toString(this.getServiceKeyDir() + "/" + keyName(request.getName()));
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
            // generate a key
            = "ssh-keygen -t dsa -C _keyname -N '' -f _keydir/_keyname && "

            // ensure the authorized_keys file exists
            + "touch _authfile && chown _chefuser _authfile && chmod 600 _authfile && "

            // ensure cloudos can read public key,
            + "chown _chefuser.rooty _keydir _keydir/_keyname.pub && "
            + "chmod 750 _keydir _keydir/_keyname.pub && "

            // ensure private key remains totally private
            + "chown root.root _keydir/_keyname && chmod 600 _keydir/_keyname && "

            // add the public key to the authorized_keys file
            + "cat _keydir/_keyname.pub >> _authfile";

    private static final String DESTROY_SCRIPT
            // strip public key from authorized_keys file
            = "temp=$(mktemp /tmp/destroy.XXXXXXX) || exit 1\n"
            + "if cat _authfile | grep -v _keyname > ${temp} ; then "
            + "    touch ${temp} && chmod 600 ${temp} && "
            + "    mv ${temp} _authfile && chown _chefuser _authfile && chmod 600 _authfile ; "
            + "else echo 'warning: key not found: _keyname' ; fi && rm -f ${temp} && "

            // remove the keys from the _keydir
            + "rm -f _keydir/_keyname _keydir/_keyname.pub\n";

    public void generateKey(String keyname) { CommandShell.execScript(GENERATE_SCRIPT
            .replace("_authfile", authfile())
            .replace("_keydir", getServiceKeyDir())
            .replace("_chefuser", getChefUser())
            .replace("_keyname", keyname));
    }

    private void destroyKey(String keyname) { CommandShell.execScript(DESTROY_SCRIPT
            .replace("_keydir", getServiceKeyDir())
            .replace("_authfile", authfile())
            .replace("_chefuser", getChefUser())
            .replace("_keyname", keyname));
    }
}
