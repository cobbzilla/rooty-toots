package rooty.toots.ssl;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import java.io.File;
import java.io.IOException;

@Slf4j
public class SslCertHandler extends RootyHandlerBase {

    @Getter @Setter private String pemPath = "/etc/ssl/certs";
    @Getter @Setter private String keyPath = "/etc/ssl/private";
    @Getter @Setter private String cacertsFile = "/usr/lib/jvm/java-7-openjdk-amd64/jre/lib/security/cacerts";
    @Getter @Setter private String keystorePassword = "changeit";

    @Override
    public boolean accepts(RootyMessage message) { return message instanceof SslCertMessage; }

    @Override
    public void process(RootyMessage message) {
        if (message instanceof InstallSslCertMessage) {
            addCert((InstallSslCertMessage) message);

        } else if (message instanceof RemoveSslCertMessage) {
            removeCert((RemoveSslCertMessage) message);

        } else {
            throw new IllegalArgumentException("Unrecognized message type: "+message.getClass());
        }
    }

    private void addCert(InstallSslCertMessage message) {

        if (!message.hasName()) throw new IllegalArgumentException("No name provided: "+message);

        final String name = message.getName();
        final File pemFile = new File(pemPath, name + ".pem");
        final File keyFile = new File(keyPath, name + ".key");

        try {
            FileUtil.touch(keyFile);
            CommandShell.chmod(keyFile, "600");

            FileUtil.touch(pemFile);
            CommandShell.chmod(pemFile, "644");
        } catch (IOException e) {
            throw new IllegalStateException("Error setting permissions on SSL cert files: "+e);
        }
        FileUtil.toFileOrDie(pemFile, message.getData().getPem());
        FileUtil.toFileOrDie(keyFile, message.getData().getKey());

        deleteFromKeystore(name);
        addToKeystore(name, pemFile);
    }

    private void addToKeystore(String name, File pemFile) {
        final CommandLine keytoolAdd = new CommandLine("keytool")
                .addArgument("-import")
                .addArgument("-alias").addArgument("cloudos_"+ name)
                .addArgument("-keypass").addArgument(keystorePassword)
                .addArgument("-keystore").addArgument(cacertsFile)
                .addArgument("-file").addArgument(pemFile.getAbsolutePath());
        try {
            CommandShell.exec(keytoolAdd, keystorePassword + "\nyes\n");
        } catch (IOException e) {
            throw new IllegalArgumentException("Error running keytool: "+e, e);
        }
    }

    private void removeCert(RemoveSslCertMessage message) {

        final String name = message.getName();

        final File pemFile = new File(pemPath, name + ".pem");
        final File keyFile = new File(keyPath, name + ".key");

        if (!pemFile.delete()) log.error("Error deleting pem file: "+pemFile.getAbsolutePath());
        if (!keyFile.delete()) log.error("Error deleting key file: "+pemFile.getAbsolutePath());

        deleteFromKeystore(name);
    }

    private void deleteFromKeystore(String name) {
        final CommandLine keytoolDelete = new CommandLine("keytool")
                .addArgument("-delete")
                .addArgument("-alias").addArgument("cloudos_"+ name)
                .addArgument("-keypass").addArgument(keystorePassword)
                .addArgument("-keystore").addArgument(cacertsFile);
        try {
            CommandShell.exec(keytoolDelete, keystorePassword + "\n");
        } catch (IOException e) {
            throw new IllegalArgumentException("Error running keytool: "+e, e);
        }
    }

}
