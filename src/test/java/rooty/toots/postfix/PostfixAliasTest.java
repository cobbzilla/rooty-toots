package rooty.toots.postfix;

import org.junit.Test;
import rooty.events.email.NewEmailAliasEvent;

import static org.cobbzilla.util.io.StreamUtil.loadResourceAsString;
import static org.cobbzilla.util.json.JsonUtil.fromJson;
import static org.junit.Assert.assertTrue;
import static rooty.toots.postfix.PostfixHandler.containsCircularReference;

public class PostfixAliasTest {

    private static final String[] TRUE_TESTS = new String[] {
            "has_circular_1.json",
            "has_circular_2.json"
    };

    private static final String[] FALSE_TESTS = new String[] {
            "no_circular_1.json"
    };

    @Test
    public void testCircularReference () throws Exception {
        for (String json : TRUE_TESTS) {
            assertTrue(containsCircularReference("alias",
                    fromJson(loadResourceAsString(json), NewEmailAliasEvent[].class)));
        }
        for (String json : FALSE_TESTS) {
            assertTrue(!containsCircularReference("alias",
                    fromJson(loadResourceAsString(json), NewEmailAliasEvent[].class)));
        }
    }

}
