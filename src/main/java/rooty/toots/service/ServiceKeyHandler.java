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
import rooty.toots.vendor.VendorSettingDisplayValue;
import rooty.toots.vendor.VendorSettingHandler;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileFilter;
import java.util.List;

import static org.cobbzilla.util.daemon.ZillaRuntime.die;
import static org.cobbzilla.util.daemon.ZillaRuntime.empty;
import static org.cobbzilla.util.http.HttpUtil.DEFAULT_CERT_NAME;
import static org.cobbzilla.util.io.FileUtil.abs;
import static org.cobbzilla.util.io.FileUtil.mkdirOrDie;
import static org.cobbzilla.util.json.JsonUtil.toJson;
import static rooty.toots.ssl.SslCertHandler.DEFAULT_SSL_KEY_PATH;

@Slf4j
public class ServiceKeyHandler extends AbstractChefHandler {

    public static final String KEYNAME_PREFIX = "servicekey_";
    public static final String KEYNAME_SUFFIX = "_dsa";

    // ssh keys go here (RSA/DSA pub/private pairs)
    @Getter @Setter private String serviceDir = "/etc/ssl/service";
    @Getter @Setter private String serviceKeyEndpoint;

    // default https keys go here
    @Getter @Setter private String defaultSslFile = DEFAULT_SSL_KEY_PATH+"/"+DEFAULT_CERT_NAME+".key";
    @Getter @Setter private String defaultSslKeySha;

    private FileFilter defaultSslKeyFilter = new FileFilter() {
        @Override public boolean accept(File f) {
            return !empty(defaultSslKeySha) && ShaUtil.sha256_file(f).equals(defaultSslKeySha);
        }
    };

    @Getter(lazy=true) private final String serviceKeyDir = initServiceKeyDir();
    public String initServiceKeyDir() { return abs(mkdirOrDie(new File(getServiceDir()))); }

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
                            request.setError("Cannot send key to customer while default key is still installed");
                            request.setResults("{err.serviceKey.cloudsteadLocked}");
                        } else {
                            // Add key to message result, it will be encrypted and put into memcached
                            // client can read it back from there to display to the end user.
                            generateKey(keyname);
                            request.setResults(FileUtil.toStringOrDie(new File(this.getServiceKeyDir(), keyname)));
                        }
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
        if (empty(defaultSslFile)) {
            log.error("No defaultSslFile specified!");
            return true;
        }
        final File defaultSsl = new File(defaultSslFile);
        if (defaultSsl.exists() && ShaUtil.sha256_file(defaultSsl).equals(defaultSslKeySha)) return true;

        try {
            int count = Integer.parseInt(CommandShell.execScript("find " + getVendorKeyRootPaths() + " -type f -exec grep -l -- \"" + FileUtil.toString(defaultSsl) + "\" {} \\; | wc -l | tr -d ' '").trim());
            return count == 0;

        } catch (Exception e) {
            log.warn("Error looking for default key: "+e, e);
            return true;
        }
    }

    protected String getVendorKeyRootPaths() { return "/etc /home"; }

    public void sendVendorMessage(ServiceKeyRequest request) {
        if (empty(serviceKeyEndpoint)) die("sendVendorMessage: No serviceKeyEndpoint defined");
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
                die("endpoint did not return 200: "+response);
            }

        } catch (Exception e) {
            die("Error sending key to serviceKeyEndpoint ("+ serviceKeyEndpoint +"): "+e, e);
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

    public void destroyKey(String keyname) { CommandShell.execScript(DESTROY_SCRIPT
            .replace("_keydir", getServiceKeyDir())
            .replace("_authfile", authfile())
            .replace("_chefuser", getChefUser())
            .replace("_keyname", keyname));
    }
}
