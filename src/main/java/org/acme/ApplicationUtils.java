package org.acme;

import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

public class ApplicationUtils {

    private static final Logger log = LoggerFactory.getLogger(ApplicationUtils.class);

    // removeJson(jsonObject, "ab.g");
    static void removeJson(JsonObject jsonObject, String key) {
        if (key.contains(".")) {
            String innerKey = key.substring(0, key.indexOf("."));
            String remaining = key.substring(key.indexOf(".") + 1);
            if (jsonObject.containsKey(innerKey)) {
                removeJson(jsonObject.getJsonObject(innerKey), remaining);
            } else {
                JsonObject innerJson = new JsonObject();
                jsonObject.put(innerKey, innerJson);
                removeJson(innerJson, remaining);
            }
        } else {
            jsonObject.remove(key);
        }
    }

    // addJson(jsonObject, "ab.g", "foo2");
    static void addJson(JsonObject jsonObject, String key, String value) {
        if (key.contains(".")) {
            String innerKey = key.substring(0, key.indexOf("."));
            String remaining = key.substring(key.indexOf(".") + 1);
            if (jsonObject.containsKey(innerKey)) {
                addJson(jsonObject.getJsonObject(innerKey), remaining, value);
            } else {
                JsonObject innerJson = new JsonObject();
                jsonObject.put(innerKey, innerJson);
                addJson(innerJson, remaining, value);
            }
        } else {
            jsonObject.put(key, value);
        }
    }

    public static String readFile(String fileName) {
        String contents = null;
        try (InputStream inputStream = ApplicationUtils.class.getClassLoader().getResourceAsStream(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            contents = reader.lines()
                    .collect(Collectors.joining(System.lineSeparator()));
        } catch (IOException e) {
            log.warn(">>> Caught exception whilst reading file (" + fileName + ") " + e);
        }
        return contents;
    }

}
