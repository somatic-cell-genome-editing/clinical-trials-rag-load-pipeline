package edu.mcw.scge.vectorstore;

import com.pgvector.PGvector;
import edu.mcw.scge.model.DocumentEmbeddingOpenAI;
import edu.mcw.scge.repository.DocumentEmbeddingOpenAIRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class PostgresVectorStoreOpenAI implements VectorStore {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresVectorStoreOpenAI.class);
    private final DocumentEmbeddingOpenAIRepository repository;
    private final EmbeddingModel embeddingModel;

    public PostgresVectorStoreOpenAI(DocumentEmbeddingOpenAIRepository repository, EmbeddingModel embeddingModel) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void add(List<Document> documents) {
        LOG.info("Adding {} documents to OpenAI vector store", documents.size());

        for (Document doc : documents) {
            try {
                // Generate embedding for the document content
                float[] embedding = embeddingModel.embed(List.of(doc.getContent())).get(0);

                // Create and save the document embedding
                DocumentEmbeddingOpenAI docEmbedding = new DocumentEmbeddingOpenAI();
                docEmbedding.setChunk(doc.getContent());
                docEmbedding.setEmbedding(new PGvector(embedding));
                docEmbedding.setFileName(doc.getMetadata().getOrDefault("filename", "unknown").toString());
                docEmbedding.setCreatedAt(LocalDateTime.now());

                repository.save(docEmbedding);
                LOG.debug("Saved document chunk: {} characters from {}",
                        doc.getContent().length(), docEmbedding.getFileName());

            } catch (Exception e) {
                LOG.error("Failed to add document to OpenAI vector store: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to add document to OpenAI vector store", e);
            }
        }

        LOG.info("Successfully added all {} documents to OpenAI vector store", documents.size());
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        LOG.info("Starting OpenAI similarity search for query: '{}'", request.getQuery());
        LOG.info("Search parameters - TopK: {}, Similarity threshold: {}",
                request.getTopK(), request.getSimilarityThreshold());

        try {
            // Generate embedding for the search query
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(request.getQuery()));
            float[] queryEmbedding = response.getResults().get(0).getOutput();
            LOG.debug("Generated query embedding vector of size: {}", queryEmbedding.length);

            // Find nearest neighbors from the database
            List<DocumentEmbeddingOpenAI> nearest;
            if (request.getSimilarityThreshold() > 0) {
                nearest = repository.findNearestNeighborsWithThreshold(
                        queryEmbedding, request.getTopK(), request.getSimilarityThreshold());
                LOG.info("Using similarity threshold: {}", request.getSimilarityThreshold());
            } else {
                nearest = repository.findNearestNeighbors(queryEmbedding, request.getTopK());
            }

            LOG.info("Found {} documents in OpenAI database", nearest.size());

            // Convert to Document objects
            List<Document> results = nearest.stream()
                    .map(de -> {
                        Map<String, Object> metadata = Map.of(
                                "filename", de.getFileName(),
                                "id", de.getId(),
                                "created_at", de.getCreatedAt()
                        );
                        return new Document(de.getChunk(), metadata);
                    })
                    .collect(Collectors.toList());

            // Log some details about the returned documents
            for (int i = 0; i < Math.min(3, results.size()); i++) {
                Document doc = results.get(i);
                LOG.debug("Result {}: {} characters from {}",
                        i + 1, doc.getContent().length(),
                        doc.getMetadata().get("filename"));
            }

            LOG.info("Returning {} documents from OpenAI similarity search", results.size());
            return results;

        } catch (Exception e) {
            LOG.error("Error during OpenAI similarity search: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI similarity search failed", e);
        }
    }

    @Override
    public Optional<Boolean> delete(List<String> ids) {
        LOG.warn("Delete operation called but not implemented");
        throw new UnsupportedOperationException("Delete operation not implemented");
    }

    // Additional helper method to check vector store health
    public long getDocumentCount() {
        long count = repository.count();
        LOG.info("OpenAI vector store contains {} documents", count);
        return count;
    }

    // Method to get unique filenames in the vector store
    public List<String> getAvailableFiles() {
        return repository.findDistinctFileNames();
    }
}
