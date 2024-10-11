package com.krickert.search.api.test.base;

import com.krickert.search.model.pipe.PipeDocument;
import com.krickert.search.model.test.util.TestDataHelper;
import org.apache.solr.common.SolrInputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

public abstract class AbstractInlineTest extends BaseSearchApiTest {

    protected static final Logger log = LoggerFactory.getLogger(AbstractInlineTest.class);

    @Override
    public void setUp() {
        super.setUp();
        try {
            seedCollection();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    protected void seedCollection() throws Exception {
        Collection<PipeDocument> docs = TestDataHelper.getFewHunderedPipeDocuments();
        List<SolrInputDocument> solrDocs = Collections.synchronizedList(new ArrayList<>());
        // Use a custom ForkJoinPool to limit parallelism if needed
        ForkJoinPool customThreadPool = new ForkJoinPool(8); // Adjust parallelism as needed

        try {
            customThreadPool.submit(() ->
                    docs.parallelStream().forEach(pipeDocument -> {
                        try {
                            SolrInputDocument inputDocument = protobufToSolrDocument.convertProtobufToSolrDocument(pipeDocument);
                            // Use VectorService to get embeddings (with caching enabled)
                            List<Float> titleVector = vectorService.getEmbeddingForText(pipeDocument.getTitle());
                            List<Float> bodyVector = vectorService.getEmbeddingForText(pipeDocument.getBody());

                            // Add vectors to the document
                            inputDocument.addField("title-vector", titleVector);
                            inputDocument.addField("body-vector", bodyVector);

                            inputDocument.addField("type", "document");

                            solrDocs.add(inputDocument);
                        } catch (Exception e) {
                            log.error("Error processing document with ID {}: {}", pipeDocument.getId(), e.getMessage());
                            throw new RuntimeException(e);
                        }
                    })
            ).get(); // Wait for completion
        } catch (ExecutionException | InterruptedException e) {
            log.error("Error during parallel document processing: {}", e.getMessage());
            throw e;
        } finally {
            customThreadPool.shutdown();
        }

        // Add documents to Solr
        try {
            solrClient.add(getCollectionName(), solrDocs);
            solrClient.commit(getChunkCollectionName());
            log.info("Added {} documents to Solr collection '{}'.", solrDocs.size(), getCollectionName());
        } catch (Exception e) {
            log.error("Failed to add documents to Solr: {}", e.getMessage());
            throw new RuntimeException("Failed to add documents to Solr", e);
        }
    }


}
