package org.acme;

import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.ingest.PutPipelineRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.GetAliasesResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.client.indices.*;
import org.elasticsearch.cluster.metadata.AliasMetadata;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Path("/index")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class IndexResource {

    private final Logger log = LoggerFactory.getLogger(IndexResource.class);

    @Inject
    RestHighLevelClient restHighLevelClient;

    @ConfigProperty(name = "quarkus.elasticsearch.username")
    String elasticClusterUsername;
    @ConfigProperty(name = "quarkus.elasticsearch.password")
    String elasticClusterPassword;
    @ConfigProperty(name = "quarkus.elasticsearch.protocol")
    String elasticScheme;
    @ConfigProperty(name = "quarkus.elasticsearch.hosts")
    String elasticCluster;

    @GET
    @Path("/create/{sourceIndex}/{numShards}/{numReplicas}/{delete}")
    @Operation(operationId = "recreate", summary = "recreate index", description = "This operation (re)creates an index. Must set increment=true to add a new index version AND/OR set delete=true to remove old version.", deprecated = false, hidden = false)
    @Tag(name = "Admin")
    @APIResponse(responseCode = "200", description = "Action successful", content = @Content(schema = @Schema(implementation = Response.class), examples = @ExampleObject(name = "example", value = "{\"reason\": \"Index Name (engagements) recreated OK.\",\"indexName\": \"engagements-000001\"}")))
    @APIResponse(responseCode = "400", description = "Unable to process input", content = @Content(schema = @Schema(implementation = Response.class), examples = @ExampleObject(name = "example", value = "{\"reason\": \"Cowardly, refusing to NOT increment or delete: engagements\",\"indexName\": \"engagements-000001\"}")))
    //@APIResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = Response.class)))
    @APIResponse(responseCode = "500", description = "Server Error", content = @Content(schema = @Schema(implementation = Response.class), examples = @ExampleObject(name = "example", value = "{\"reason\": \"Failed to create ingest pipeline\",\"indexName\": \"engagements-000001\"}")))
    public Response recreateIndex(
            @Parameter(description = "The source index name to create. Excludes the postfix version e.g. -000001", required = true, schema = @Schema(type = SchemaType.STRING)) @DefaultValue("engagements") @PathParam("sourceIndex") String sourceIndex,
            @Parameter(description = "The number of shards to create.", required = true, schema = @Schema(type = SchemaType.STRING)) @DefaultValue("1") @PathParam("numShards") String numShards,
            @Parameter(description = "The number of replicas to create.", required = true, schema = @Schema(type = SchemaType.STRING)) @DefaultValue("0") @PathParam("numReplicas") String numReplicas,
            @Parameter(description = "Delete the index if it exists.", required = true, schema = @Schema(type = SchemaType.BOOLEAN)) @DefaultValue("false") @PathParam("delete") Boolean delete
    ) {
        // we only support engagements indexes
        if (sourceIndex != null
                && !sourceIndex.equalsIgnoreCase("engagements")) {
            log.warn(">>> Index not Found: " + sourceIndex);
            JsonObject reason = new JsonObject().put("reason", "Index Name (" + sourceIndex + ") not known.");
            reason.put("indexName", sourceIndex);
            return Response.status(Response.Status.BAD_REQUEST).entity(reason).build();
        }
        // ensure index template configuration is sound
        if (numReplicas == null || numReplicas.isEmpty()) {
            numReplicas = "1";
        }
        if (numReplicas == null || numReplicas.isEmpty()) {
            numReplicas = "1";
        }
        // ingest pipeline configuration
        String pipeline = ApplicationUtils.readFile("engagements-ingest-pipeline.json");
        if (null == pipeline) {
            JsonObject reason = new JsonObject().put("reason", "Failed to read engagements-ingest-pipeline.json");
            reason.put("indexName", sourceIndex);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(reason).build();
        }
        PutPipelineRequest pipelineRequest = new PutPipelineRequest(
                "engagements-default-pipeline",
                new BytesArray(pipeline.getBytes(StandardCharsets.UTF_8)),
                XContentType.JSON);
        // check index exists and get postfix
        GetIndexRequest requestIndices = new GetIndexRequest("*");
        GetIndexResponse responseIndices = null;
        try {
            responseIndices = restHighLevelClient.indices().get(requestIndices, RequestOptions.DEFAULT);
        } catch (IOException e) {
            JsonObject reason = new JsonObject().put("reason", "Failed to to fetch all indices " + e);
            reason.put("indexName", sourceIndex);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(reason).build();
        }
        String[] indices = responseIndices.getIndices();
        String postfix = "000000";
        for (String indexName : indices) {
            Pattern index = Pattern.compile("^" + sourceIndex + "-(\\d+)$");
            Matcher matchSource = index.matcher(indexName);
            if (matchSource.find()) {
                // get the largest postfix increment
                if (Integer.parseInt(matchSource.group(1)) > Integer.parseInt(postfix)) {
                    postfix = matchSource.group(1);
                }
            }
        }
        String indexName = sourceIndex + "-" + postfix;
        String previousName = null;
        // 000000 index should never exist
        if (!postfix.equalsIgnoreCase("000000")) {
            previousName = indexName;
        }
        // delete index configuration
        DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(indexName);
        // increment postfix, if previousName null, we always start at 000001
        DecimalFormat df = new DecimalFormat("000000");
        postfix = df.format(Integer.parseInt(postfix) + 1L);
        indexName = sourceIndex + "-" + postfix;

        // create index configuration
        String index = ApplicationUtils.readFile("engagements-index.json");
        if (null == index) {
            log.warn(">>> Failed to read file engagements-index.json");
            JsonObject reason = new JsonObject().put("reason", "Failed to read file engagements-index.json");
            reason.put("indexName", indexName);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(reason).build();
        }
        // dont create aliases if we are not deleting
        if (delete == false && previousName != null) {
            JsonObject indexObject = new JsonObject(index);
            ApplicationUtils.removeJson(indexObject, "aliases");
            index = indexObject.toString();
        }
        if ((delete == true) && previousName != null) { // increment and delete, make sure we don't create two read index's
            JsonObject indexObject = new JsonObject(index);
            ApplicationUtils.removeJson(indexObject, "aliases." + sourceIndex + "-read");
            index = indexObject.toString();
        }
        CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);
        createIndexRequest.source(
                new BytesArray(index.getBytes(StandardCharsets.UTF_8)),
                XContentType.JSON);
        // index template configuration
        PutIndexTemplateRequest engagementsTemplate = new PutIndexTemplateRequest("engagements-template")
                .patterns(Arrays.asList("engagements-*"))
                .settings(Settings.builder()
                        .put("index.max_ngram_diff", 50)
                        .put("index.number_of_replicas", Integer.parseInt(numReplicas))
                        .put("index.number_of_shards", Integer.parseInt(numReplicas))
                        .put("index.default_pipeline", "engagements-default-pipeline"));
        // delete and recreate
        try {
            if (sourceIndex.equalsIgnoreCase("engagements")) {
                AcknowledgedResponse pipelineReponse = restHighLevelClient.ingest().putPipeline(pipelineRequest,
                        RequestOptions.DEFAULT);
                if (!pipelineReponse.isAcknowledged()) {
                    log.warn(">>> Failed to create ingest pipeline");
                    JsonObject reason = new JsonObject().put("reason", "Failed to create ingest pipeline");
                    reason.put("indexName", indexName);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(reason).build();
                }
                AcknowledgedResponse templateResponse = restHighLevelClient.indices().putTemplate(engagementsTemplate,
                        RequestOptions.DEFAULT);
                if (!templateResponse.isAcknowledged()) {
                    log.warn(">>> Failed to create index template");
                    JsonObject reason = new JsonObject().put("reason", "Failed to create index template");
                    reason.put("indexName", indexName);
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(reason).build();
                }
                if (delete == true) {
                    try {
                        restHighLevelClient.indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
                    } catch (Exception e) {
                        // fine
                    }
                }
                CreateIndexResponse createIndexResponse = restHighLevelClient.indices().create(createIndexRequest,
                        RequestOptions.DEFAULT);
                if (!createIndexResponse.isAcknowledged()) {
                    log.warn(">>> Failed to create index");
                    JsonObject reason = new JsonObject().put("reason", "Failed to create index");
                    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(reason).build();
                }
                if (delete == false && previousName != null) {
                    Response writeResponse = switchWriteIndex(previousName, indexName);
                    if (writeResponse.getStatus() != Response.Status.OK.getStatusCode()) {
                        JsonObject reason = new JsonObject().put("reason", "Index Write Alias Switched from (" + previousName
                                + "->" + indexName + ") Failed (" + writeResponse + ")");
                        reason.put("indexName", indexName);
                        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(reason).build();
                    }
                }
            } else {
                log.warn(">>> Index Name (" + sourceIndex + ") not supported");
                JsonObject reason = new JsonObject().put("reason", "Index Name (" + sourceIndex + ") not supported");
                reason.put("indexName", sourceIndex);
                return Response.status(Response.Status.BAD_REQUEST).entity(reason).build();
            }
        } catch (IOException e) {
            log.warn(">>> Couldn't connect to the elasticsearch " + e);
            JsonObject reason = new JsonObject().put("reason",
                    "Couldn't connect to the elasticsearch server to create necessary templates. Ensure the Elasticsearch user has permissions to create templates.");
            reason.put("indexName", indexName);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(reason).build();
        }

        JsonObject reason = new JsonObject().put("reason", "Index Name (" + sourceIndex + ") recreated OK.");
        reason.put("indexName", indexName);
        return Response.ok(reason).build();
    }

    @GET
    @Path("/switch/write/{sourceIndex}/{targetIndex}")
    @Operation(operationId = "switch", summary = "switch write index alias", description = "This operation switches the write alias", deprecated = false, hidden = false)
    @Tag(name = "Admin")
    public Response switchWriteIndex(@PathParam("sourceIndex") String sourceIndex,
                                     @PathParam("targetIndex") String targetIndex) {
        if (sourceIndex.isBlank() || targetIndex.isBlank())
            return Response.notModified().build();
        log.info(">>> switching write alias for " + sourceIndex + "->" + targetIndex);
        Pattern index = Pattern.compile("^(\\w+)-(\\d+)$");
        Matcher matchSource = index.matcher(sourceIndex);
        String sourceIndexName = new String();
        String sourceIndexNumber = new String();
        if (matchSource.find()) {
            sourceIndexName = matchSource.group(1);
            sourceIndexNumber = matchSource.group(2);
        } else {
            log.warn(">>> switch failed to match source index pattern (" + sourceIndex + ")");
            return Response.serverError().build();
        }
        Matcher matchTarget = index.matcher(sourceIndex);
        String targetIndexName = new String();
        String targetIndexNumber = new String();
        if (matchTarget.find()) {
            targetIndexName = matchSource.group(1);
            targetIndexNumber = matchSource.group(2);
        } else {
            log.warn(">>> switch failed to match target index pattern (" + targetIndex + ")");
            return Response.serverError().build();
        }
        IndicesAliasesRequest indicesAliasesRequest = new IndicesAliasesRequest();
        IndicesAliasesRequest.AliasActions deleteWriteAlias = new IndicesAliasesRequest.AliasActions(
                IndicesAliasesRequest.AliasActions.Type.REMOVE)
                .index(sourceIndex).alias(sourceIndexName + "-write");
        IndicesAliasesRequest.AliasActions addWriteAlias = new IndicesAliasesRequest.AliasActions(
                IndicesAliasesRequest.AliasActions.Type.ADD)
                .index(targetIndex).alias(targetIndexName + "-write").writeIndex(true);
        indicesAliasesRequest.addAliasAction(deleteWriteAlias);
        indicesAliasesRequest.addAliasAction(addWriteAlias);
        try {
            AcknowledgedResponse indicesAliasesResponse = restHighLevelClient.indices().updateAliases(indicesAliasesRequest,
                    RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.warn(">>> switch failed add/remove index write alias (" + sourceIndex + ")->(" + targetIndex + ") "
                    + e.getLocalizedMessage());
            return Response.serverError().build();
        }
        JsonObject reason = new JsonObject().put("reason",
                "Index Write Alias Switched from (" + sourceIndex + "->" + targetIndex + ") OK.");
        return Response.ok(reason).build();
    }

    @GET
    @Path("/switch/read/{targetIndex}")
    @Operation(operationId = "switchForTarget", summary = "switch read index alias to target", description = "This operation switches the read alias given a target only", deprecated = false, hidden = false)
    @Tag(name = "Admin")
    public Response switchReadIndexForTarget(@PathParam("targetIndex") String targetIndex) {
        if (targetIndex.isBlank())
            return Response.notModified().build();
        log.info(">>> switching read alias for " + targetIndex);
        IndicesAliasesRequest indicesAliasesRequest = new IndicesAliasesRequest();
        GetAliasesRequest request = new GetAliasesRequest();
        Pattern index = Pattern.compile("^(\\w+)-(\\d+)$");
        Matcher matchSource = index.matcher(targetIndex);
        String targetIndexName = new String();
        if (matchSource.find()) {
            targetIndexName = matchSource.group(1);
        } else {
            log.warn(">>> switch failed to match target index pattern (" + targetIndex + ")");
            return Response.serverError().build();
        }
        GetAliasesRequest requestWithAlias = new GetAliasesRequest();
        String sourceIndex = null;
        try {
            GetAliasesResponse getAliasResponse = restHighLevelClient.indices().getAlias(request, RequestOptions.DEFAULT);
            Map<String, Set<AliasMetadata>> aliases = getAliasResponse.getAliases();
            Set<String> indices = aliases.keySet();
            for (String key : indices) {
                Set<AliasMetadata> md = aliases.get(key);
                for (AliasMetadata m : md) {
                    if ((targetIndexName + "-read").equalsIgnoreCase(m.getAlias()) && m.writeIndex() == false) {
                        log.info(">>> index read found (" + key + ")");
                        sourceIndex = key;
                    }
                }
            }

        } catch (IOException e) {
            log.warn(">>> switch failed to find read index alias (" + targetIndex + ") "
                    + e.getLocalizedMessage());
            return Response.serverError().build();
        }

        IndicesAliasesRequest.AliasActions deleteReadAlias = new IndicesAliasesRequest.AliasActions(
                IndicesAliasesRequest.AliasActions.Type.REMOVE)
                .index(sourceIndex).alias(targetIndexName + "-read");
        IndicesAliasesRequest.AliasActions addReadAlias = new IndicesAliasesRequest.AliasActions(
                IndicesAliasesRequest.AliasActions.Type.ADD)
                .index(targetIndex).alias(targetIndexName + "-read").writeIndex(false);
        indicesAliasesRequest.addAliasAction(deleteReadAlias);
        indicesAliasesRequest.addAliasAction(addReadAlias);
        try {
            AcknowledgedResponse indicesAliasesResponse = restHighLevelClient.indices().updateAliases(indicesAliasesRequest,
                    RequestOptions.DEFAULT);
        } catch (IOException e) {
            log.warn(">>> switch failed add/remove read index alias (" + sourceIndex + ")->(" + targetIndex + ") "
                    + e.getLocalizedMessage());
            return Response.serverError().build();
        }
        JsonObject reason = new JsonObject().put("reason",
                "Index Read Alias Switched from (" + sourceIndex + "->" + targetIndex + ") OK.");
        return Response.ok(reason).build();
    }

    @GET
    @Path("/count/{sourceIndex}")
    @Operation(operationId = "count", summary = "count documents in index", description = "This operation returns the document count in the index", deprecated = false, hidden = false)
    @Tag(name = "Admin")
    public Response count(@PathParam("sourceIndex") @DefaultValue("engagements-read") String sourceIndex) {
        if (sourceIndex.isBlank())
            return Response.notModified().build();
        CountRequest countRequest = new CountRequest(sourceIndex);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(QueryBuilders.matchAllQuery());
        countRequest.source(searchSourceBuilder);
        long count = 0L;
        try {
            CountResponse countResponse = restHighLevelClient.count(countRequest, RequestOptions.DEFAULT);
            count = countResponse.getCount();
        } catch (Exception e) {
            log.warn(">>> count failed for index (" + sourceIndex + ") " + e.getMessage());
            return Response.serverError().build();
        }
        JsonObject reason = new JsonObject().put("count", count);
        log.info(">>> count (" + sourceIndex + ") = " + count);
        reason.put("reason", "Index count (" + sourceIndex + ") OK.");
        return Response.ok(reason).build();
    }

    @GET
    @Path("/prune/{keep}/{sourceIndex}")
    @Tag(name = "Admin")
    @Operation(operationId = "prune", summary = "prune indexes", description = "This operation prunes (deletes) engagements indexes, keeping last (n) indexes. Optionally can be passed an index name.", deprecated = false, hidden = false)
    @APIResponse(responseCode = "200", description = "Action successful", content = @Content(schema = @Schema(implementation = Response.class), examples = @ExampleObject(name = "example", value = "{\"reason\": \"Prune Indexes OK.\",\"deleted\": \"[engagements-000003, engagements-000004]\",\"count\": \"2\"}")))
    @APIResponse(responseCode = "400", description = "Unable to process input", content = @Content(schema = @Schema(implementation = Response.class)))
    @APIResponse(responseCode = "500", description = "Server Error", content = @Content(schema = @Schema(implementation = Response.class)))
    public Response prune(
            @Parameter(description = "The number of indexes to keep. Excludes indexes with aliases these are always kept.", required = true, schema = @Schema(type = SchemaType.INTEGER)) @DefaultValue("5") @PathParam("keep") int keep,
            @Parameter(description = "The source index name to delete. Excludes the postfix version e.g. -000001", required = false, schema = @Schema(type = SchemaType.STRING)) @DefaultValue("all") @PathParam("sourceIndex") String sourceIndex
    ) {
        // choose an index to recreate
        if (sourceIndex != null
                && !sourceIndex.equalsIgnoreCase("all")
                && !sourceIndex.equalsIgnoreCase("engagements")) {
            log.warn(">>> Index not Found: " + sourceIndex);
            JsonObject reason = new JsonObject().put("reason", "Index Name (" + sourceIndex + ") not known.");
            reason.put("indexName", sourceIndex);
            return Response.status(Response.Status.BAD_REQUEST).entity(reason).build();
        }
        log.info(">>> prune index for (" + sourceIndex + ":" + keep + ") started OK.");
        // find matching indexes
        GetIndexRequest request = null;
        if (sourceIndex.equalsIgnoreCase("all")) {
            request = new GetIndexRequest("*engagements-*");
        } else {
            request = new GetIndexRequest(sourceIndex + "-*");
        }

        request.local(false);
        request.humanReadable(true);
        request.includeDefaults(false);
        request.indicesOptions(IndicesOptions.LENIENT_EXPAND_OPEN);

        GetIndexResponse responseIndices = null;
        try {
            responseIndices = restHighLevelClient.indices().get(request, RequestOptions.DEFAULT);

        } catch (IOException e) {
            log.warn(">>> prune call failed to get indices " + e.getMessage());
            return Response.serverError().build();
        }

        // find prune candidates
        Map<String, Map<Integer, String>> pruneMe = new HashMap<>();
        for (String indexName : responseIndices.getIndices()) {
            Pattern index = Pattern.compile("^(\\w+)-(\\d+)$");
            Matcher matchSource = index.matcher(indexName);
            String sourceIndexName = new String();
            String sourceIndexNumber = new String();
            if (matchSource.find()) {
                sourceIndexName = matchSource.group(1);
                sourceIndexNumber = matchSource.group(2);
            } else {
                log.warn(">>> prune failed to match source index pattern (" + indexName + ")");
                return Response.serverError().build();
            }
            log.debug(">>> found " + sourceIndexName + " " + sourceIndexNumber);

            Map<Integer, String> candidates = null;
            if (null == pruneMe.get(sourceIndexName)) {
                candidates = new HashMap();
            } else {
                candidates = pruneMe.get(sourceIndexName);
            }
            candidates.put(Integer.valueOf(sourceIndexNumber), indexName);
            pruneMe.put(sourceIndexName, candidates);
        }

        // remove candidates with aliases
        Map<String, List<AliasMetadata>> aliases = responseIndices.getAliases();
        Set<String> indices = aliases.keySet();
        for (String key : indices) {
            List<AliasMetadata> md = aliases.get(key);
            for (AliasMetadata m : md) {
                log.debug(">>> alias found " + m.getAlias() + "->" + key);

                Pattern index = Pattern.compile("^(\\w+)-(\\d+)$");
                Matcher matchSource = index.matcher(key);
                String sourceIndexName = new String();
                String sourceIndexNumber = new String();
                if (matchSource.find()) {
                    sourceIndexName = matchSource.group(1);
                    sourceIndexNumber = matchSource.group(2);
                } else {
                    log.warn(">>> prune failed to match source index pattern (" + key + ")");
                    return Response.serverError().build();
                }
                Map<Integer, String> candidates = null;
                if (null != pruneMe.get(sourceIndexName)) {
                    candidates = pruneMe.get(sourceIndexName);
                    if (null != candidates && null != candidates.get(Integer.valueOf(sourceIndexNumber))) {
                        log.debug(">>> removing " + sourceIndexName + "->" + Integer.valueOf(sourceIndexNumber));
                        candidates.remove(Integer.valueOf(sourceIndexNumber));
                    }
                }
            }
        }

        log.debug(">>> pruneMe contains:");
        pruneMe.entrySet().stream()
                .forEach(e -> log.debug(e.getKey() + ":" + e.getValue()));

        // sort each set, prune (n) keep
        List<Map.Entry<Integer, String>> deleteList = null;
        List<String> deleted = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, String>> entry : pruneMe.entrySet()) {
            log.debug(">>> pruneMe: " + entry.getKey());
            Set<Map.Entry<Integer, String>> entries = entry.getValue().entrySet();
            int k = entries.size() - keep;
            Stream<Map.Entry<Integer, String>> sorted = entries.stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).limit(k < 0 ? 0 : k);
            deleteList = sorted.collect(Collectors.toList());

            // do the deletion synchronously
            for (Map.Entry<Integer, String> deleteMe : deleteList) {
                log.info(">>> Deleting " + deleteMe.getValue());
                DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(deleteMe.getValue());
                try {
                    AcknowledgedResponse deleteIndexResponse = restHighLevelClient.indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
                    if (deleteIndexResponse.isAcknowledged()) {
                        deleted.add(deleteMe.getValue());
                    }
                } catch (IOException e) {
                    log.warn(">>> delete failed for index, continuing (" + deleteMe.getValue() + ") " + e.getMessage());
                }
            }
        }
        JsonObject reason = new JsonObject().put("reason", "Prune Indexes OK.");
        reason.put("deleted", deleted.toString());
        reason.put("count", deleted.size());
        return Response.ok(reason).build();
    }


    @GET
    @Path("/aliases")
    @Tag(name = "Admin")
    @Operation(operationId = "getAliases", summary = "Get All engagements Index Aliases", description = "This operation returns all engagements index aliases.", deprecated = false, hidden = false)
    @APIResponse(responseCode = "200", description = "Action successful", content = @Content(schema = @Schema(implementation = Response.class), examples = @ExampleObject(name = "example", value = "{\"reason\": \"Get Aliases OK.\",\"aliases\": {\"engagements-read\": \"engagements-000001\",\"engagements-write\": \"engagements-000001\"}}")))
    @APIResponse(responseCode = "400", description = "Unable to process input", content = @Content(schema = @Schema(implementation = Response.class)))
    @APIResponse(responseCode = "500", description = "Server Error", content = @Content(schema = @Schema(implementation = Response.class)))
    public Response getAliases() {
        GetAliasesRequest request = new GetAliasesRequest("*engagements-*");
        Map<String, String> ali = new HashMap<>();
        try {
            GetAliasesResponse getAliasResponse = restHighLevelClient.indices().getAlias(request, RequestOptions.DEFAULT);
            Map<String, Set<AliasMetadata>> aliases = getAliasResponse.getAliases();
            Set<String> indices = aliases.keySet();
            for (String key : indices) {
                Set<AliasMetadata> md = aliases.get(key);
                for (AliasMetadata m : md) {
                    log.info(">>> alias found (" + m.getAlias() + "->" + key + ")");
                    ali.put(m.getAlias(), key);
                }
            }

        } catch (IOException e) {
            log.warn(">>> getAliases failed with: " + e.getLocalizedMessage());
            return Response.serverError().build();
        }
        JsonObject reason = new JsonObject().put("reason", "Get Aliases OK.");
        reason.put("aliases", ali);
        return Response.ok(reason).build();
    }

}
