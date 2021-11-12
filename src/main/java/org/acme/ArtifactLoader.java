package org.acme;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import org.acme.rest.client.ArtifactClient;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.client.exception.ResteasyWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static java.util.stream.Collectors.toMap;

@Path("/artifact")
@Tag(name = "Artifact Admin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class ArtifactLoader {

    private final Logger log = LoggerFactory.getLogger(ArtifactLoader.class);

    @Inject
    @RestClient
    ArtifactClient artifactClient;

    @Inject
    EngagementList engagementList;

    Map engagementMap = new HashMap<String, Engagement>();

    @Inject
    JsonUtils jsonUtils;

    @Inject
    Downloader downloader;

    @GET
    @Blocking
    @Path("/load/weeklyreports")
    @SecurityRequirement(name = "jwt", scopes = {})
    @Operation(operationId = "weeklyreports", summary = "load weekly reports", description = "This operation loads weekly reports into elasticsearch", deprecated = false, hidden = false)
    public Response loadArtifacts() {
        log.info(">>> loading engagement list");
        ArrayList<Engagement> engagements;
        try {
            engagements = engagementList.engagements();
            engagementMap = engagements.stream().collect(toMap(e -> e.uuid(), e -> e));
        } catch (ResteasyWebApplicationException e) {
            log.warn(e.getLocalizedMessage());
            return e.unwrap().getResponse();
        }
        log.info(">>> loading weekly report artifacts");
        ArrayList<Artifact> artifacts;
        artifacts = jsonUtils.mapWeeklyReportArtifactResponse(artifactClient.getAllWeeklyReports("artifacts.type=weeklyReport"));
        processArtifacts(artifacts);
        JsonObject reason = new JsonObject().put(artifacts.size() + " artifacts processed", "OK.");
        return Response.ok().entity(reason).build();
    }

    private void processArtifacts(ArrayList<Artifact> artifacts) {
        log.info(">>> about to processArtifacts: " + artifacts.size());
        for (Artifact a: artifacts) {
            if (engagementMap.containsKey(a.uuid())) {
                Engagement e = (Engagement) engagementMap.get(a.uuid());
                downloader.export(a.link_address(), a.uuid(), e.name());
            } else {
                log.warn(">>> Odd, no engagement.uuid found for: " + a.uuid() + " skipping.");
            }
        }
    }

}
