package org.acme;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

@ApplicationScoped
public class IndexLifecycle {

    @Inject
    IndexResource indexResource;

    @ConfigProperty(name = "onstartup.create.index")
    boolean onStart;

    void onStart(@Observes StartupEvent ev) {
        Response response = indexResource.count("engagements-read");
        if (onStart && response.getStatus() != Response.Status.OK.getStatusCode()) {
            indexResource.recreateIndex("engagements", "1", "0", false);
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        // empty
    }
}
