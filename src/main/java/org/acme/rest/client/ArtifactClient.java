package org.acme.rest.client;

import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.*;

@ApplicationScoped
@RegisterRestClient(configKey = "lodestar.backend.api")
@RegisterClientHeaders
@Produces("application/json")
@Consumes("application/json")
@ClientHeaderParam(name = "Accept-version", value = "v1")
@ClientHeaderParam(name = "Origin", value = "https://lodestar.rht-labs.com")
@ClientHeaderParam(name = "accept", value = "*/*")
public interface ArtifactClient {

    @GET
    @Path("/engagements/artifacts")
    String getAllWeeklyReports(@QueryParam("search") String search, @QueryParam("perPage") @DefaultValue("1000") Integer perPage, @QueryParam("page") @DefaultValue("1") Integer page);

}
