package rooty.toots.chef;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.io.CompositeBufferedFilesystemWatcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.cobbzilla.util.daemon.ZillaRuntime.die;

@Slf4j
public class ChefDirSynchronizer extends CompositeBufferedFilesystemWatcher {

    public static final long CHEF_FS_EVENTS_TIMEOUT = TimeUnit.SECONDS.toMillis(60);
    public static final int MAX_CHEF_FS_EVENTS = 1000;
    public static final int MAX_CHEF_SYNC_ERRORS = 10;

    @Getter private File target;
    private int errorCount = 0;

    public ChefDirSynchronizer (String[] paths, File target) {
        super(CHEF_FS_EVENTS_TIMEOUT, MAX_CHEF_FS_EVENTS, paths);
        this.target = target;
    }

    public ChefDirSynchronizer (String paths, File target) {
        this(paths.split("[,\\s]+"), target);
    }

    public void fire() { fire(null); }

    @Override public synchronized void fire(List<WatchEvent<?>> events) {
        try {
            if (!isEmpty() || events == null) {
                ChefSolo.merge(dirsWatching(), target);
                errorCount = 0;
            }
        } catch (IOException e) {
            log.warn("Error merging chef dir: " + e, e);
            if (errorCount++ > MAX_CHEF_SYNC_ERRORS) {
                final String msg = "Too many errors sync'ing chef dir (" + MAX_CHEF_SYNC_ERRORS + "), dying";
                log.error(msg);
                try { close(); } catch (IOException e1) {
                    log.error("error closing self: "+e1, e1);
                }
                die(msg, e);
            }
        }
    }

}
