package rooty.toots.postfix;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.string.StringUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;
import rooty.events.account.AccountEvent;
import rooty.events.account.NewAccountEvent;
import rooty.events.account.RemoveAccountEvent;
import rooty.events.email.EmailDomainEvent;
import rooty.events.email.NewEmailDomainEvent;
import rooty.events.email.RemoveEmailDomainEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.*;

@Slf4j
public class PostfixHandler extends RootyHandlerBase {

    @Getter @Setter private String vmailbox;
    @Getter @Setter private String vmailboxDir;

    @Setter private String mainCf;
    public String getMainCf () { return StringUtil.empty(mainCf) ? "/etc/postfix/main.cf" : mainCf; }

    @Getter(value=AccessLevel.PROTECTED, lazy=true) private final File vmailboxFile = initVmailboxFile();
    private File initVmailboxFile() { return new File(vmailbox); }

    @Getter(value=AccessLevel.PROTECTED, lazy=true) private final File primaryDomainFile = initPrimaryDomainFile();
    private File initPrimaryDomainFile() { return new File(vmailbox+".primaryDomain"); }

    @Getter(value=AccessLevel.PROTECTED, lazy=true) private final File usersFile = initUsersFile();
    private File initUsersFile() { return new File(vmailbox+".users"); }

    @Getter(value=AccessLevel.PROTECTED, lazy=true) private final File domainsFile = initDomainsFile();
    private File initDomainsFile() { return new File(vmailbox+".domains"); }

    @Getter(value=AccessLevel.PROTECTED, lazy=true) private final File adminFile = initAdminFile();
    private File initAdminFile() { return new File(vmailbox+".admin"); }

    @Getter(value=AccessLevel.PROTECTED, lazy=true) private final String localDomain = initLocalDomain();
    private String initLocalDomain() { return CommandShell.hostname(); }

    private List<String> listFromFile(File f) {

        if (!f.exists()) return Collections.emptyList();

        final List<String> strings;
        try {
            strings = FileUtil.toStringList(f);
        } catch (Exception e) {
            throw new IllegalStateException("Error reading from file "+f.getAbsolutePath()+": "+e, e);
        }

        // omit empty strings
        final List<String> result = new ArrayList<>();
        for (String s : strings) {
            if (!StringUtil.empty(s)) result.add(s);
        }
        return result;
    }

    private String getPrimaryDomain () {
        try {
            return FileUtil.toString(getPrimaryDomainFile());
        } catch (Exception e) {
            log.warn("error reading from primaryDomain file: "+getPrimaryDomainFile().getAbsolutePath()+": "+e+", returning "+getLocalDomain());
            return getLocalDomain();
        }
    }

    private void setPrimaryDomain (String domain) throws IOException {
        FileUtil.toFile(getPrimaryDomainFile(), domain);
    }

    protected Set<String> getUsers () { return new LinkedHashSet<>(listFromFile(getUsersFile())); }
    private void addUser(String user) throws IOException {
        try (Writer writer = new FileWriter(getUsersFile(), true)) {
            writer.write("\n"+user);
        }
    }

    private void setUsers(Set<String> users) throws IOException {
        try (Writer writer = new FileWriter(getUsersFile())) {
            writer.write(StringUtil.toString(users, "\n"));
        }
    }

    protected Set<String> getDomains() {
        final List<String> domains = new ArrayList<>(listFromFile(getDomainsFile()));
        domains.add(0, getLocalDomain());
        return new LinkedHashSet<>(domains);
    }

    private void addDomain(String domain) throws IOException {
        try (Writer writer = new FileWriter(getDomainsFile(), true)) {
            writer.write("\n"+domain);
        }
    }

    private void setDomains(Set<String> domains) throws IOException {
        try (Writer writer = new FileWriter(getDomainsFile())) {
            writer.write(StringUtil.toString(domains, "\n"));
        }
    }

    protected String getAdmin() {
        final File f = getAdminFile();
        if (!f.exists()) return null;
        try {
            return FileUtil.toString(f);
        } catch (Exception e) {
            log.warn("Error reading from admin file ("+ f.getAbsolutePath()+"), returning null: "+e);
            return null;
        }
    }

    private void setAdmin (String admin) throws IOException { FileUtil.toFile(getAdminFile(), admin); }

    private void digest() throws IOException { PostfixDigester.digest(this); }

    @Override public boolean accepts(RootyMessage message) {
        return message instanceof AccountEvent
                || message instanceof EmailDomainEvent;
    }

    private interface Processor<T extends RootyMessage> {
        public void process(T message) throws IOException;
    }

    private final Processor newAccountProcessor = new Processor<NewAccountEvent>() {
        @Override public void process(NewAccountEvent message) throws IOException { handleAddAccount(message); }
    };

    private final Processor removeAccountProcessor = new Processor<RemoveAccountEvent>() {
        @Override public void process(RemoveAccountEvent message) throws IOException { handleRemoveAccount(message); }
    };

    private final Processor newDomainProcessor = new Processor<NewEmailDomainEvent>() {
        @Override public void process(NewEmailDomainEvent message) throws IOException { handleAddDomain(message); }
    };

    private final Processor removeDomainProcessor = new Processor<RemoveEmailDomainEvent>() {
        @Override public void process(RemoveEmailDomainEvent message) throws IOException { handleRemoveDomain(message); }
    };

    @Getter(value=AccessLevel.PRIVATE, lazy=true) private final Map<Class, Processor> processorMap = initProcessorMap();
    private Map<Class, Processor> initProcessorMap() {
        final Map<Class, Processor> map = new HashMap<>();
        map.put(NewAccountEvent.class, newAccountProcessor);
        map.put(RemoveAccountEvent.class, removeAccountProcessor);
        map.put(NewEmailDomainEvent.class, newDomainProcessor);
        map.put(RemoveEmailDomainEvent.class, removeDomainProcessor);
        return map;
    }

    public synchronized void process(RootyMessage message) {
        try {
            final Processor p = getProcessorMap().get(message.getClass());
            if (p == null) {
                log.warn("No processor found for "+message.getClass()+": "+message);
                return;
            }
            p.process(message);

        } catch (Exception e) {
            final String msg = "Error processing message ("+message+"): "+e;
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    private void handleAddAccount(NewAccountEvent event) throws IOException {

        // refuse to re-add immutable accounts
        final String username = event.getName();
        if (event.isReservedAccount()) {
            log.warn("Cannot add immutable account: " + username);
            return;
        }

        boolean doDigest = false;
        final Set<String> users = getUsers();
        if (!users.contains(username)) {
            addUser(username);
            doDigest = true;
        }

        if (event.isAdmin()) {
            String admin = getAdmin();
            if (admin == null) {
                symlinkToPostmaster(username);
                setAdmin(username);
                doDigest = true;

            } else {
                log.warn("Admin already set to " + admin + ", not changing to " + username);
            }
        }

        if (doDigest) digest();
    }

    private void symlinkToPostmaster(String username) {
        final String postmasterDir = getLocalDomain() + "/postmaster";
        final File postmasterFullDir = new File(vmailboxDir, postmasterDir);
        final File adminDir = new File(vmailboxDir, getLocalDomain()  +"/"+ username);
        if (adminDir.exists()) {
            throw new IllegalArgumentException("dir already exists, cannot symlink it to postmaster: "+adminDir.getAbsolutePath());
        }
        try {
            Files.createSymbolicLink(FileUtil.path(adminDir), FileUtil.path(postmasterFullDir));
        } catch (IOException e) {
            final String msg = "Error creating symlink from " + adminDir.getAbsolutePath() + " -> " + postmasterFullDir.getAbsolutePath() + ": " + e;
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    private void handleRemoveAccount(RemoveAccountEvent message) throws IOException {

        // refuse to remove root/postmaster
        final String username = message.getName();
        if (message.isReservedAccount()) {
            log.warn("handleRemoveAccount: silently refusing to remove reserved account: " + username);
            return;
        }

        if (username.equals(getAdmin())) {
            log.error("handleRemoveAccount: cannot remove admin account: "+username);
            return;
        }

        final Set<String> users = getUsers();
        if (users.contains(username)) {
            users.remove(username);
            setUsers(users);
            digest();
        }
    }

    private void handleAddDomain (NewEmailDomainEvent message) throws IOException {

        // todo: validate domain?
        final String domain = message.getName();

        final Set<String> domains = getDomains();
        if (!domains.contains(domain)) {
            addDomain(domain);
            digest();
        }
    }

    private void handleRemoveDomain (RemoveEmailDomainEvent message) throws IOException {
        final String domain = message.getName();
        final Set<String> domains = getDomains();
        if (domains.contains(domain)) {
            domains.remove(domain);
            setDomains(domains);
            digest();
        }
    }

}
