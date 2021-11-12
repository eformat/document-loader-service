package org.acme;

import org.acme.rest.client.EngagementClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.client.exception.ResteasyWebApplicationException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;

@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EngagementList {

    @Inject
    @RestClient
    EngagementClient engagementClient;

    @Inject
    JsonUtils jsonUtils;

    public ArrayList<Engagement> engagements() throws ResteasyWebApplicationException {
        return jsonUtils.mapEngagementResponse(engagementClient.getAllEngagements("commits"));
    }

}
