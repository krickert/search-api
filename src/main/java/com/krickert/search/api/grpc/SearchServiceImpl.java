package com.krickert.search.api.grpc;

import com.krickert.search.api.SearchRequest;
import com.krickert.search.api.SearchResponse;
import com.krickert.search.api.SearchServiceGrpc;
import com.krickert.search.api.config.CollectionConfig;
import com.krickert.search.api.config.SearchApiConfig;
import com.krickert.search.api.grpc.mapper.query.SolrQueryBuilder;
import com.krickert.search.api.grpc.mapper.query.SolrQueryData;
import com.krickert.search.api.grpc.mapper.response.ResponseMapper;
import com.krickert.search.api.solr.SolrService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@GrpcService
public class SearchServiceImpl extends SearchServiceGrpc.SearchServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    private final SolrService solrService;
    private final CollectionConfig collectionConfig;
    private final SolrQueryBuilder solrQueryBuilder;
    private final ResponseMapper responseMapper;

    @Inject
    public SearchServiceImpl(SolrService solrService, SearchApiConfig config,
                             SolrQueryBuilder solrQueryBuilder, ResponseMapper responseMapper) {
        this.solrService = solrService;
        this.collectionConfig = config.getSolr().getCollectionConfig();
        this.solrQueryBuilder = solrQueryBuilder;
        this.responseMapper = responseMapper;
    }

    @Override
    public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
        try {
            // Build Solr query parameters based on the request and configuration
            SolrQueryData solrQueryData = solrQueryBuilder.buildSolrQueryParams(request);

            // Execute the Solr query
            QueryResponse solrResponse = solrService.query(collectionConfig.getCollectionName(), solrQueryData.getQueryParams());

            // Parse the Solr response into the gRPC SearchResponse
            SearchResponse searchResponse = responseMapper.mapToSearchResponse(solrResponse, request);

            // Send the response back to the client
            responseObserver.onNext(searchResponse);
            responseObserver.onCompleted();
        } catch (SolrServerException e) {
            log.error("SolrServerException during search operation: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("SolrServerException: " + e.getMessage()).withCause(e).asRuntimeException());
        } catch (IOException e) {
            log.error("IOException during search operation: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL.withDescription("IOException: " + e.getMessage()).withCause(e).asRuntimeException());
        } catch (Exception e) {
            log.error("Unexpected error during search operation: {}", e.getMessage(), e);
            responseObserver.onError(Status.UNKNOWN.withDescription("Unexpected error: " + e.getMessage()).withCause(e).asRuntimeException());
        }
    }
}
