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
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Path("gdrive")
@Tag(name = "GDrive Admin")
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

    @Path("export")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response export(@QueryParam("url") String url, @QueryParam("uuid") String uuid, @QueryParam("project") String project) {
        Pattern pattern = Pattern.compile("https://\\w+.google.com/\\w+/(?:folders|u/1/folders|\\w)/([a-zA-Z0-9-_]+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            String id = matcher.group(1);
            if (url.contains("folder")) {
                return exportFolder(id, uuid, project);
            } else if (url.contains("document") || url.contains("file")) {
                return exportFile(id, uuid, project);
            }
        }
        log.warn(">>> url should contain google folder or file or document " + url);
        return Response.status(Response.Status.BAD_REQUEST).build();
    }

    @Path("exportFile")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response exportFile(@QueryParam("fileId") String fileId, @QueryParam("uuid") String uuid, @QueryParam("project") String project) {
        bus.<String>request("file", new FID(fileId, uuid, project));
        return Response.ok("Exporting file: " + fileId + " async OK").build();
    }

    @Path("folderList")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response folderList(@QueryParam("folderId") String folderId) {
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
    public Response exportFolder(@QueryParam("folderId") String folderId, @QueryParam("uuid") String uuid, @QueryParam("project") String project) {
        log.info(">>> exportFolder: " + folderId);
        ChildList response = producerTemplate.requestBody("google-drive://drive-children/list?inBody=folderId", folderId, ChildList.class);
        if (response != null) {
            response.getItems().forEach(
                    child -> bus.<String>request("file", new FID(child.getId(), uuid, project))
            );
            return Response.ok("Exporting " + response.getItems().size() + " documents async OK").build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @ConsumeEvent(value = "file", blocking = true)
    public void consumeFID(FID f) throws InterruptedException {
        try {
            consumeFile(f);
        } catch (CamelExecutionException e) {
            log.warn("Caught " + e);
        }
    }

    public void consumeFile(FID fid) {
        log.info(">>> exportFile: " + fid.id);
        try {
            File response = producerTemplate.requestBody("google-drive://drive-files/get?inBody=fileId", fid.id, File.class);
            if (response != null) {
                HttpResponse resp = null;
                String fileName = null;
                // We don't use export method of camel component, rather shortcut cause we know the feed download url's
                try {
                    switch (response.getMimeType()) {
                        case ("application/pdf"):
                            resp = getClient(producerTemplate.getCamelContext()).getRequestFactory()
                                    .buildGetRequest(new GenericUrl("https://www.googleapis.com/drive/v2/files/" + fid.id + "?alt=media&source=downloadUrl")).execute();
                            fileName = downloadFolder.concat("/" + fid.project + "-=-" + fid.uuid + "-=-" +  fid.id + "-=-" + response.getTitle().replaceAll("[^a-zA-Z0-9\\.\\-]", "_").strip());
                            break;
                        case ("application/vnd.google-apps.document"):
                            String ext = ".docx";
                            resp = getClient(producerTemplate.getCamelContext()).getRequestFactory()
                                    .buildGetRequest(new GenericUrl("https://docs.google.com/feeds/download/documents/export/Export?id=" + fid.id + "&exportFormat=" + ext.substring(1))).execute();
                            fileName = downloadFolder.concat("/" + fid.project + "-=-" + fid.uuid + "-=-" + fid.id + "-=-" + response.getTitle().replaceAll("[^a-zA-Z0-9\\.\\-]", "_").strip().concat(ext));
                            break;
                        default:
                            log.warn(">>> Unsupported mimeType: " + response.getMimeType());
                    }
                    try (FileOutputStream fileOutputStream = new FileOutputStream(fileName);
                         FileChannel channel = fileOutputStream.getChannel();
                         FileLock lock = channel.lock()) {
                        resp.download(fileOutputStream);
                    }

                } catch (IOException e) {
                    log.warn(">>> Something wrong, failed to write fileId: " + fid.id + " " + fileName);
                }
            } else {
                log.warn(">>> Something wrong, could not find fileId: " + fid.id);
            }

        } catch (CamelExecutionException e) {
            Exception exchangeException = e.getExchange().getException();
            if (exchangeException != null && exchangeException.getCause() instanceof GoogleJsonResponseException) {
                GoogleJsonResponseException originalException = (GoogleJsonResponseException) exchangeException.getCause();
            }
            throw e;
        }
    }

    public class FID {
        public String id;
        public String uuid;
        public String project;

        FID(String id, String uuid, String project) {
            this.id = id;
            this.uuid = uuid;
            this.project = project;
        }
    }
}
