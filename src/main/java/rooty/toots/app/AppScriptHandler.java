package rooty.toots.app;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.cobbzilla.util.security.ShaUtil;
import org.cobbzilla.util.system.CommandResult;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AppScriptHandler extends RootyHandlerBase {

    public static final long MAX_MESSAGE_AGE = TimeUnit.SECONDS.toMillis(10);
    @Getter @Setter private String app;
    @Getter @Setter private Map<String, String> scripts = new HashMap<>();
    @Getter @Setter private Map<String, String> script_digests = new HashMap<>();

    @Override public boolean accepts(RootyMessage message) {
        return message instanceof AppScriptMessage && ((AppScriptMessage) message).isApp(app);
    }

    @Override public boolean process(RootyMessage message) {

        final AppScriptMessage scriptMessage = (AppScriptMessage) message;
        final AppScriptMessageType type = scriptMessage.getType();
        final List<String> args = scriptMessage.getArgs();
        final int numArgs = args.size();

        if (message.isOlderThan(MAX_MESSAGE_AGE)) {
            log.warn("dropping old message (aged "+message.getAge()+"ms)");
            return true;
        }

        // some safety checks for potential abusers
        if (numArgs > 10) throw new IllegalArgumentException("Too many arguments");
        for (String arg : args) if (arg.length() > 200) throw new IllegalArgumentException("Argument was too long: "+arg);

        // It must be the same file that was installed with the app
        if (type == null) throw new IllegalArgumentException("No script type");
        final String typeName = type.name();
        final String pathname = scripts.get(typeName);
        if (pathname == null) throw new IllegalArgumentException("No script found for type: "+ typeName);
        final File executable = new File(pathname);

        final String expectedShasum = script_digests.get(typeName);
        final String actualShasum = ShaUtil.sha256_file(executable);
        if (!actualShasum.equals(expectedShasum)) {
            throw new IllegalStateException("Shasum of "+executable.getAbsolutePath()+" changed. Expected "+expectedShasum+", was "+actualShasum);
        }

        final CommandLine command = new CommandLine(executable);
        for (String arg : args) command.addArgument(arg);
        final CommandResult result;
        Boolean success;
        try {
            result = CommandShell.exec(command, type.getExitValues());
            success = result.isZeroExitStatus();

        } catch (Exception e) {
            throw new IllegalStateException("Error executing shasum of executable: "+executable.getAbsolutePath()+": "+e, e);
        }

        message.setResults(success.toString());
        return true;
    }
}
