package org.acme;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.parsetools.JsonParser;

import javax.enterprise.context.ApplicationScoped;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class JsonUtils {

    public ArrayList<Engagement> mapEngagementResponse(String engagements) {
        Object e = parse(Buffer.buffer(engagements));
        final ArrayList<Engagement> arrayList = new ArrayList<>();
        final Iterator<Object> i = ((JsonArray)e).iterator();
        while (i.hasNext()) {
            final JsonObject element = (JsonObject) i.next();
            arrayList.add(new Engagement(element.getString("uuid"), element.getString("customer_name").concat(" - ").concat(element.getString("project_name"))));
        }
        return arrayList;
    }

    public ArrayList<Artifact> mapWeeklyReportArtifactResponse(String engagements) {
        Object e = parse(Buffer.buffer(engagements));
        final ArrayList<Artifact> arrayList = new ArrayList<>();
        final Iterator<Object> i = ((JsonArray)e).iterator();
        while (i.hasNext()) {
            final JsonObject element = (JsonObject) i.next();
            arrayList.add(new Artifact(element.getString("engagement_uuid"), element.getString("link_address")));
        }
        return arrayList;
    }

    private Object parse(Buffer buffer) {
        JsonParser parser = JsonParser.newParser();
        AtomicReference<Object> result = new AtomicReference<>();
        parser.handler(event -> {
            switch (event.type()) {
                case VALUE:
                    Object res = result.get();
                    if (res == null) {
                        result.set(event.value());
                    } else if (res instanceof List) {
                        List list = (List) res;
                        list.add(event.value());
                    } else if (res instanceof Map) {
                        Map map = (Map) res;
                        map.put(event.fieldName(), event.value());
                    }
                    break;
                case START_ARRAY:
                    result.set(new ArrayList());
                    parser.objectValueMode();
                    break;
                case START_OBJECT:
                    result.set(new HashMap());
                    parser.objectValueMode();
                    break;
            }
        });
        parser.handle(buffer);
        parser.end();
        Object res = result.get();
        if (res instanceof List) {
            return new JsonArray((List) res);
        }
        if (res instanceof Map) {
            return new JsonObject((Map<String, Object>) res);
        }
        return res;
    }
}
