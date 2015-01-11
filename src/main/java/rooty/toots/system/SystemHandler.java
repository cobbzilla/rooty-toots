package rooty.toots.system;

import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.io.FileUtil;
import org.cobbzilla.util.system.CommandShell;
import rooty.RootyHandlerBase;
import rooty.RootyMessage;

import java.io.File;

@Slf4j
public class SystemHandler extends RootyHandlerBase {

    public static final String ETC_TIMEZONE = "/etc/timezone";

    @Override
    public boolean accepts(RootyMessage message) {
        return message instanceof SystemMessage;
    }

    @Override
    public boolean process(RootyMessage message) {
        if (message instanceof SystemSetTimezoneMessage) {

            SystemSetTimezoneMessage tzMessage = (SystemSetTimezoneMessage) message;
            final String unixZoneName = tzMessage.getTimezone();
            if (!new File("/usr/share/zoneinfo/"+unixZoneName).exists()) {
                throw new IllegalArgumentException("Invalid timezone name: "+unixZoneName);
            }

            final String savedTimezone;
            try {
                savedTimezone = FileUtil.toString(ETC_TIMEZONE);
            } catch (Exception e) {
                throw new IllegalStateException("Error reading "+ETC_TIMEZONE+": "+e, e);
            }

            try {
                FileUtil.toFile(ETC_TIMEZONE, unixZoneName);
            } catch (Exception e) {
                throw new IllegalStateException("Error writing "+ETC_TIMEZONE+": "+e, e);
            }

            try {
                CommandShell.exec("dpkg-reconfigure --frontend noninteractive tzdata");
            } catch (Exception e) {
                try {
                    FileUtil.toFile(ETC_TIMEZONE, savedTimezone);
                    CommandShell.exec("dpkg-reconfigure --frontend noninteractive tzdata");
                } catch (Exception e1) {
                    log.warn("Error rolling back timezone to "+savedTimezone+" :" +e1, e1);
                }
                throw new IllegalStateException("Error reconfiguring system time", e);
            }
            return true;

        } else {
            log.warn("Unsupported message: "+message.getClass().getName());
            return false;
        }
    }
}
