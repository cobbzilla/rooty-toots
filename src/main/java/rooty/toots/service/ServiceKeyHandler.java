package rooty.toots.service;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.http.*;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.security.ShaUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileFilter;

import static org.cobbzilla.util.json.JsonUtil.toJson;

@Slf4j
public class ServiceKeyHandler extends RootyHandlerBase {

    public static final String KEYNAME_PREFIX = "cloudos_";
    public static final String KEYNAME_SUFFIX = "_dsa";

    @Getter @Setter private String serviceDir = "/etc/ssl/service";
    @Getter @Setter private String vendorEndpoint;

    @Getter @Setter private String sslKeysDir;
    @Getter @Setter private String defaultKeySha;

    @Getter(value=AccessLevel.PROTECTED, lazy=true) private final String chefUser = initChefUser();
    protected String initChefUser() { return FileUtil.toStringOrDie("/etc/chef-user").trim(); }

    private FileFilter defaultKeyFilter = new FileFilter() {
        @Override public boolean accept(File f) {
            try {
                return ShaUtil.sha256_file(f).equals(defaultKeySha);
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
    public boolean accepts(RootyMessage message) { return message instanceof ServiceKeyRequest; }

    public static String keyname(String name) { return KEYNAME_PREFIX + name + KEYNAME_SUFFIX; }

    @Override
    public void process(RootyMessage message) {

        final ServiceKeyRequest request = (ServiceKeyRequest) message;
        final String keyname = keyname(request.getName());

        switch (request.getOperation()) {
            case ALLOW_SSH:
                message.setResults(String.valueOf(!vendorKeyExists()));
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
                        // cloudos can read it back from there to display to the end user.
                        generateKey(keyname);
                        message.setResults(FileUtil.toStringOrDie(new File(getKeyDir(), keyname)));
                }
                break;

            case DESTROY:
                destroyKey(keyname);
                break;
        }
    }

    public boolean vendorKeyExists() {
        return new File(sslKeysDir).listFiles(defaultKeyFilter).length > 0;
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

    // todo: move these scripts elsewhere? install with cloudos?
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
