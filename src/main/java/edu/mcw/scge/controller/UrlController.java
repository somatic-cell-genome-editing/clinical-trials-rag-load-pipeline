package edu.mcw.scge.controller;

import edu.mcw.scge.reader.UrlDocumentReader;
import edu.mcw.scge.service.DocumentPreprocessor;
import edu.mcw.scge.repository.DocumentEmbeddingOpenAIRepository;
import edu.mcw.scge.model.DocumentEmbeddingOpenAI;
import edu.mcw.scge.dao.DataSourceFactory;
import edu.mcw.scge.dao.implementation.ClinicalTrailDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class UrlController {
    private static final Logger LOG = LoggerFactory.getLogger(UrlController.class);

    private final VectorStore openaiVectorStore;
    private final DocumentPreprocessor preprocessor;
    private final DocumentEmbeddingOpenAIRepository repository;

    public UrlController(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                         DocumentPreprocessor preprocessor,
                         DocumentEmbeddingOpenAIRepository repository){
        this.openaiVectorStore = openaiVectorStore;
        this.preprocessor = preprocessor;
        this.repository = repository;
    }

    public void loadClinicalTrials() {
        LOG.info("Starting clinical trials loading process");

        try {
            // Get NCT IDs from scgeplatformcur database
            DataSource curationDS = DataSourceFactory.getInstance().getScgePlatformDataSource();
            ClinicalTrailDAO dao = new ClinicalTrailDAO(curationDS);
            List<String> nctIds = dao.getAllNctIds();
            LOG.info("Retrieved {} NCT IDs from scgeplatformcur database", nctIds.size());

            List<String> processed = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            List<String> overwritten = new ArrayList<>();

            for (String nctId : nctIds) {
                try {
                    if (nctId == null || nctId.trim().isEmpty()) {
                        LOG.warn("Skipping empty nctId");
                        continue;
                    }

                    nctId = nctId.trim();
                    String url = "https://scge.mcw.edu/platform/data/report/clinicalTrials/" + nctId;

                    LOG.info("Processing trial: {}", nctId);

                    // Check if already exists in vector store
                    List<DocumentEmbeddingOpenAI> existing = repository.findByFileName("CLINICAL TRIAL: " + nctId);
                    boolean isOverwrite = !existing.isEmpty();

                    // If exists, delete existing entries first
                    if (isOverwrite) {
                        for (DocumentEmbeddingOpenAI doc : existing) {
                            repository.delete(doc);
                        }
                        LOG.info("Deleted {} existing entries for trial: {}", existing.size(), nctId);
                        overwritten.add(nctId);
                    }

                    boolean success = processUrlInternal(url);

                    if (success) {
                        processed.add(nctId);
                        LOG.info("Successfully processed trial: {} ({})", nctId, isOverwrite ? "overwritten" : "new");
                    } else {
                        failed.add(nctId);
                        LOG.error("Failed to process trial: {}", nctId);
                    }

                } catch (Exception e) {
                    String safeNctId = nctId != null ? nctId.trim() : "unknown";
                    failed.add(safeNctId);
                    LOG.error("Exception processing trial: {}", safeNctId, e);
                }
            }

            LOG.info("Clinical trials processing complete. Total: {}, Processed: {}, Overwritten: {}, Failed: {}",
                    nctIds.size(), processed.size(), overwritten.size(), failed.size());
            LOG.info("Processed trials: {}", processed);
            LOG.info("Overwritten trials: {}", overwritten);
            LOG.info("Failed trials: {}", failed);

        } catch (Exception e) {
            LOG.error("Error during clinical trials loading", e);
            throw new RuntimeException("Failed to load clinical trials: " + e.getMessage(), e);
        }
    }

    private boolean processUrlInternal(String urlString) {
        try {
            // Fetch content from URL
            UrlDocumentReader documentReader = new UrlDocumentReader(urlString);
            List<Document> documents = documentReader.get();

            if (documents.isEmpty()) {
                LOG.error("Failed to fetch content from URL: {}", urlString);
                return false;
            }

            // Fix the metadata issue - add filename by creating new documents with mutable metadata
            List<Document> documentsWithFilename = documents.stream()
                    .map(doc -> {
                        Map<String, Object> mutableMetadata = new HashMap<>(doc.getMetadata());
                        mutableMetadata.put("filename", extractFilenameFromUrl(urlString));
                        return new Document(doc.getContent(), mutableMetadata);
                    })
                    .collect(Collectors.toList());

            documents = documentsWithFilename;

            // STEP 1: Universal preprocessing for ANY document type
            List<Document> preprocessedDocs = preprocessor.preprocessDocuments(documents);
            LOG.debug("Preprocessed into {} clean documents", preprocessedDocs.size());

            if (preprocessedDocs.isEmpty()) {
                LOG.error("No usable content after preprocessing");
                throw new RuntimeException("Document preprocessing failed - no usable content found");
            }

            // STEP 2: Split into chunks with correct Spring AI settings
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(800)                // Target chunk size in tokens
                    .withMinChunkSizeChars(200)        // Minimum characters per chunk
                    .withMinChunkLengthToEmbed(50)     // Minimum length to embed
                    .withMaxNumChunks(10000)           // Maximum number of chunks
                    .withKeepSeparator(true)           // Keep separators for readability
                    .build();

            List<Document> splitDocuments = splitter.apply(preprocessedDocs);
            LOG.debug("Split into {} chunks after preprocessing", splitDocuments.size());

            // Add to OpenAI vector store
            openaiVectorStore.add(splitDocuments);
            LOG.debug("Successfully added {} URL chunks to OpenAI vector store", splitDocuments.size());

            return true;

        } catch (Exception e) {
            LOG.error("Error processing URL: {}", urlString, e);
            return false;
        }
    }

    private String extractFilenameFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            String path = url.getPath();

            // Check if this is a clinical trial URL
            boolean isClinicalTrialUrl = urlString.contains("/clinicalTrials/report/") ||
                                         urlString.contains("/report/clinicalTrials/");

            String filename;
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                String[] pathParts = path.split("/");
                String lastPart = pathParts[pathParts.length - 1];
                if (!lastPart.isEmpty()) {
                    filename = lastPart;
                } else {
                    filename = url.getHost().replaceAll("\\.", "_");
                }
            } else {
                filename = url.getHost().replaceAll("\\.", "_");
            }

            // For clinical trials, prefix with "CLINICAL TRIAL: " for identification
            if (isClinicalTrialUrl) {
                return "CLINICAL TRIAL: " + filename;
            } else {
                return filename + ":" + urlString;
            }

        } catch (MalformedURLException e) {
            return "webpage_" + System.currentTimeMillis() + ":" + urlString;
        }
    }
}
