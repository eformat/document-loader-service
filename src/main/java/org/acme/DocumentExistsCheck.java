package org.acme;

import io.vertx.core.json.JsonObject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.elasticsearch.ElasticsearchComponent;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import javax.enterprise.context.ApplicationScoped;
import java.util.HashMap;

@ApplicationScoped
public class DocumentExistsCheck implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        ElasticsearchComponent component = exchange.getContext().getComponent("elasticsearch-rest-quarkus", ElasticsearchComponent.class);
        RestClient restClient = component.getClient();
        HashMap map = exchange.getIn().getBody(HashMap.class);
        String fileId = (String) map.get("fileId");
        String queryJson = ApplicationUtils.readFile("/find-single-file.json");
        JsonObject qJson = new JsonObject(queryJson);
        JsonObject slid = qJson
                .getJsonObject("query")
                .getJsonObject("bool")
                .getJsonArray("must").getJsonObject(0).getJsonObject("simple_query_string");
        slid.put("query", fileId);
        queryJson = qJson.encode();
        // make low level query request
        Request request = new Request(
                "POST",
                "/engagements-write/_search");
        request.setJsonEntity(queryJson);
        JsonObject json = null;
        Response response = restClient.performRequest(request);
        String responseBody = EntityUtils.toString(response.getEntity());
        json = new JsonObject(responseBody);
        if (json.getJsonObject("hits").getJsonArray("hits").size() > 0) {
            throw new FileExistsException("fileId exists " + fileId);
        }
    }

}
