package org.acme;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.elasticsearch.ElasticsearchComponent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.elasticsearch.client.RestClient;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

@ApplicationScoped
public class DocumentLoader extends RouteBuilder {

    @ConfigProperty(name = "download.folder", defaultValue = "/tmp/input")
    String downloadFolder;

    @Override
    public void configure() throws Exception {
        from("file://".concat(downloadFolder) + "?synchronous=true&delete=true&readLock=fileLock&delay=1000&include=.*.docx|.*.pdf")
                .routeId("file-poller")
                .setHeader("component").constant("elasticsearch-rest-quarkus")
                .process(new DocumentElasticConverter())
                .toD("${header.component}://elasticsearch?operation=Index&indexName=engagements-write")
                .log("file processed");
    }

    @Named("elasticsearch-rest-quarkus")
    public ElasticsearchComponent elasticsearchQuarkus(RestClient client) {
        // Use the RestClient bean created by the Quarkus ElasticSearch extension
        ElasticsearchComponent component = new ElasticsearchComponent();
        component.setClient(client);
        return component;
    }
}
