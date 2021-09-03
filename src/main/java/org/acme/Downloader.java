package org.acme;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.ChildList;
import com.google.api.services.drive.model.File;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.eventbus.EventBus;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.google.drive.GoogleDriveComponent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

@Path("gdrive")
@Tag(name = "Admin")
public class Downloader {

    private final Logger log = LoggerFactory.getLogger(Downloader.class);

    @ConfigProperty(name = "download.folder", defaultValue = "/tmp")
    String downloadFolder;

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    EventBus bus;

    private static Drive getClient(CamelContext context) {
        GoogleDriveComponent component = context.getComponent("google-drive", GoogleDriveComponent.class);
        return component.getClient(component.getConfiguration());
    }

    @Path("exportFile")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response exportFile(@QueryParam("fileId") String fileId) {
        log.info(">>> exportFile: " + fileId);
        try {
            File response = producerTemplate.requestBody("google-drive://drive-files/get?inBody=fileId", fileId, File.class);
            if (response != null) {
                HttpResponse resp = null;
                String fileName = null;
                // We don't use export method of camel component, rather shortcut cause we know the feed download url's
                try {
                    switch (response.getMimeType()) {
                        case ("application/pdf"):
                            resp = getClient(producerTemplate.getCamelContext()).getRequestFactory()
                                    .buildGetRequest(new GenericUrl("https://www.googleapis.com/drive/v2/files/" + fileId + "?alt=media&source=downloadUrl")).execute();
                            fileName = downloadFolder.concat("/" + fileId + "-=-" + response.getTitle().strip());
                            break;
                        case ("application/vnd.google-apps.document"):
                            String ext = ".docx";
                            resp = getClient(producerTemplate.getCamelContext()).getRequestFactory()
                                    .buildGetRequest(new GenericUrl("https://docs.google.com/feeds/download/documents/export/Export?id=" + fileId + "&exportFormat=" + ext.substring(1))).execute();
                            fileName = downloadFolder.concat("/" + fileId + "-=-" + response.getTitle().strip().concat(ext));
                            break;
                        default:
                            log.warn(">>> Unsupported mimeType: " + response.getMimeType());
                            return Response.status(Response.Status.BAD_REQUEST).build();
                    }
                    try (FileOutputStream fileOutputStream = new FileOutputStream(fileName);
                         FileChannel channel = fileOutputStream.getChannel();
                         FileLock lock = channel.lock()) {
                        resp.download(fileOutputStream);
                    }
                    return Response.ok(fileName).build();

                } catch (IOException e) {
                    log.warn(">>> Something wrong, failed to write fileId: " + fileId);
                    return Response.status(Response.Status.NOT_FOUND).build();
                }
            }
            log.warn(">>> Something wrong, could not find fileId: " + fileId);
            return Response.status(Response.Status.NOT_FOUND).build();

        } catch (CamelExecutionException e) {
            Exception exchangeException = e.getExchange().getException();
            if (exchangeException != null && exchangeException.getCause() instanceof GoogleJsonResponseException) {
                GoogleJsonResponseException originalException = (GoogleJsonResponseException) exchangeException.getCause();
                return Response.status(originalException.getStatusCode()).build();
            }
            throw e;
        }
    }

    @Path("folderList")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response readFolder(@QueryParam("folderId") String folderId) {
        try {
            ChildList response = producerTemplate.requestBody("google-drive://drive-children/list?inBody=folderId", folderId, ChildList.class);
            if (response != null) {
                return Response.ok(response.getItems().toString()).build();
            } else {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
        } catch (CamelExecutionException e) {
            Exception exchangeException = e.getExchange().getException();
            if (exchangeException != null && exchangeException.getCause() instanceof GoogleJsonResponseException) {
                GoogleJsonResponseException originalException = (GoogleJsonResponseException) exchangeException.getCause();
                return Response.status(originalException.getStatusCode()).build();
            }
            throw e;
        }
    }

    @Path("exportFolder")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response exportFolder(@QueryParam("folderId") String folderId) {
        log.info(">>> exportFolder: " + folderId);
        ChildList response = producerTemplate.requestBody("google-drive://drive-children/list?inBody=folderId", folderId, ChildList.class);
        if (response != null) {
            response.getItems().forEach(
                    child -> bus.<String>request("folder", child.getId())
            );
            return Response.ok("Exporting " + response.getItems().size() + " documents async OK").build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @ConsumeEvent(value = "folder", blocking = true)
    public void consumeFolder(String folderId) throws InterruptedException {
        try {
            exportFile(folderId);
        } catch (CamelExecutionException e) {
            log.warn("Caught " + e);
        }
    }
}
