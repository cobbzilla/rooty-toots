package rooty.toots.chef;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.util.system.CommandProgressCallback;
import org.cobbzilla.util.system.CommandProgressMarker;
import rooty.RootyStatusManager;

import java.util.ArrayList;
import java.util.List;

@AllArgsConstructor @Slf4j
public class ChefProgressCallback implements CommandProgressCallback {

    @Getter private ChefMessage chefMessage;
    @Getter private String queueName;
    @Getter private RootyStatusManager statusManager;

    @Getter private final List<CommandProgressMarker> progressMarkers = new ArrayList<>();

    @Override public void updateProgress (CommandProgressMarker marker) {
        progressMarkers.add(marker);
        try {
            chefMessage.setResults(JsonUtil.toJson(progressMarkers));
            statusManager.update(queueName, chefMessage, true);
        } catch (Exception e) {
            log.error("Error updating chef results: "+e, e);
        }
    }
}
