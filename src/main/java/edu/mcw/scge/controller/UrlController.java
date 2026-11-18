package edu.mcw.scge.controller;

import edu.mcw.scge.datamodel.ClinicalTrialRecord;
import edu.mcw.scge.reader.UrlDocumentReader;
import edu.mcw.scge.service.TrialSummaryBuilder;
import edu.mcw.scge.service.ChunkLabeler;
import edu.mcw.scge.service.DocumentPreprocessor;
import edu.mcw.scge.services.SCGEContext;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Component
public class UrlController {
    private static final Logger LOG = LoggerFactory.getLogger(UrlController.class);
    private static final String TRIAL_FILE_PREFIX = "CLINICAL TRIAL: ";

    private final VectorStore openaiVectorStore;
    private final DocumentPreprocessor preprocessor;
    private final DocumentEmbeddingOpenAIRepository repository;
    private final String embeddingModelName;
    private final String embeddingDimensions;
    private final String platformBaseUrl;

    public UrlController(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                         DocumentPreprocessor preprocessor,
                         DocumentEmbeddingOpenAIRepository repository,
                         @Value("${spring.ai.openai.embedding.options.model:text-embedding-ada-002}") String embeddingModelName,
                         @Value("${spring.ai.openai.embedding.options.dimensions:}") String embeddingDimensions,
                         @Value("${pipeline.platform-base-url:}") String platformBaseUrl){
        this.openaiVectorStore = openaiVectorStore;
        this.preprocessor = preprocessor;
        this.repository = repository;
        this.embeddingModelName = embeddingModelName;
        this.embeddingDimensions = embeddingDimensions;
        this.platformBaseUrl = platformBaseUrl.isBlank() ? defaultPlatformBaseUrl() : platformBaseUrl.replaceAll("/+$", "");
        LOG.info("Trial pages are read from {}", this.platformBaseUrl);
    }

    /**
     * SCGE site whose trial pages are embedded: production on the production server,
     * otherwise dev. Never stage. Override with pipeline.platform-base-url.
     */
    private static String defaultPlatformBaseUrl() {
        try {
            return SCGEContext.isProduction() ? "https://scge.mcw.edu" : "https://dev.scge.mcw.edu";
        } catch (Exception e) {
            LOG.warn("Could not determine server - using dev site for trial pages", e);
            return "https://dev.scge.mcw.edu";
        }
    }

    public void loadClinicalTrials() {
        LOG.info("Starting clinical trials loading process (embedding model: {})", embeddingModelName);

        try {
            // Get NCT IDs from the platform database of this server (scgeplatformDataSource in default_db.xml)
            DataSource platformDS = DataSourceFactory.getInstance().getScgePlatformDataSource();
            ClinicalTrailDAO dao = new ClinicalTrailDAO(platformDS);
            List<String> nctIds = dao.getAllNctIds();
            LOG.info("Retrieved {} NCT IDs from scgeplatform database", nctIds.size());

            Set<String> trialIds = new TreeSet<>();
            for (String nctId : nctIds) {
                if (nctId == null || nctId.trim().isEmpty()) {
                    LOG.warn("Skipping empty nctId");
                    continue;
                }
                trialIds.add(nctId.trim());
            }

            List<String> added = new ArrayList<>();
            List<String> updated = new ArrayList<>();
            List<String> unchanged = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            for (String nctId : trialIds) {
                try {
                    String url = platformBaseUrl + "/platform/data/report/clinicalTrials/" + nctId;
                    LOG.info("Processing trial: {}", nctId);

                    List<Document> chunks = buildChunks(url);
                    if (chunks == null) {
                        failed.add(nctId);
                        LOG.error("Failed to process trial: {} (existing entries kept)", nctId);
                        continue;
                    }

                    switch (replaceSource(TRIAL_FILE_PREFIX + nctId, chunks)) {
                        case ADDED -> added.add(nctId);
                        case UPDATED -> updated.add(nctId);
                        case UNCHANGED -> unchanged.add(nctId);
                    }

                } catch (Exception e) {
                    failed.add(nctId);
                    LOG.error("Exception processing trial: {} (existing entries kept)", nctId, e);
                }
            }

            List<String> removed = removeDeletedTrials(trialIds);
            loadTrialSummary(dao, trialIds);

            LOG.info("Clinical trials processing complete. Trials: {}, Added: {}, Updated: {}, Unchanged: {}, Failed: {}, Removed (no longer in database): {}",
                    trialIds.size(), added.size(), updated.size(), unchanged.size(), failed.size(), removed.size());
            LOG.info("Added trials: {}", added);
            LOG.info("Updated trials: {}", updated);
            LOG.info("Failed trials: {}", failed);
            LOG.info("Removed trials: {}", removed);

        } catch (Exception e) {
            LOG.error("Error during clinical trials loading", e);
            throw new RuntimeException("Failed to load clinical trials: " + e.getMessage(), e);
        }
    }

    private enum LoadResult { ADDED, UPDATED, UNCHANGED }

    /**
     * Fetch a page and turn it into labeled chunks. Returns null if the page could not be read.
     */
    private List<Document> buildChunks(String urlString) {
        try {
            // Fetch content from URL
            UrlDocumentReader documentReader = new UrlDocumentReader(urlString);
            List<Document> documents = documentReader.get();

            if (documents.isEmpty()) {
                LOG.error("Failed to fetch content from URL: {}", urlString);
                return null;
            }

            // Fix the metadata issue - add filename by creating new documents with mutable metadata
            List<Document> documentsWithFilename = documents.stream()
                    .map(doc -> {
                        Map<String, Object> mutableMetadata = new HashMap<>(doc.getMetadata());
                        mutableMetadata.put("filename", extractFilenameFromUrl(urlString));
                        return new Document(doc.getContent(), mutableMetadata);
                    })
                    .collect(Collectors.toList());

            return splitAndLabel(documentsWithFilename);

        } catch (Exception e) {
            LOG.error("Error processing URL: {}", urlString, e);
            return null;
        }
    }

    private List<Document> splitAndLabel(List<Document> documents) {
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

        // STEP 3: Prefix every chunk with its source so later chunks still name the trial
        List<Document> chunks = ChunkLabeler.applyLabels(splitter.apply(ChunkLabeler.addLabels(preprocessedDocs)));
        LOG.debug("Split into {} chunks after preprocessing", chunks.size());
        return chunks;
    }

    /**
     * Store the chunks for one source. Nothing is embedded if the stored chunks have the same
     * content hash. New rows are saved before the old ones are deleted, so a failure keeps the
     * previous version instead of leaving the source missing.
     */
    private LoadResult replaceSource(String fileName, List<Document> chunks) {
        List<DocumentEmbeddingOpenAI> existing = repository.findByFileName(fileName);
        String hash = contentHash(chunks);

        if (!existing.isEmpty() && existing.stream().allMatch(e -> hash.equals(e.getContentHash()))) {
            LOG.info("Unchanged, not re-embedded: {}", fileName);
            return LoadResult.UNCHANGED;
        }

        Set<Long> oldIds = existing.stream().map(DocumentEmbeddingOpenAI::getId).collect(Collectors.toSet());
        List<Document> hashedChunks = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                    metadata.put("filename", fileName);
                    metadata.put("content_hash", hash);
                    return new Document(chunk.getContent(), metadata);
                })
                .collect(Collectors.toList());

        try {
            openaiVectorStore.add(hashedChunks);
        } catch (RuntimeException e) {
            // Remove any partially saved new rows; the previous version stays in place
            List<DocumentEmbeddingOpenAI> partial = repository.findByFileName(fileName).stream()
                    .filter(d -> !oldIds.contains(d.getId()))
                    .collect(Collectors.toList());
            repository.deleteAll(partial);
            throw e;
        }

        repository.deleteAll(existing);
        LOG.info("{} {} ({} chunks)", existing.isEmpty() ? "Added" : "Updated", fileName, hashedChunks.size());
        return existing.isEmpty() ? LoadResult.ADDED : LoadResult.UPDATED;
    }

    /**
     * SHA-256 of the embedding model and the chunk texts, so a model change also forces re-embedding.
     */
    private String contentHash(List<Document> chunks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((embeddingModelName + "|" + embeddingDimensions + "\n").getBytes(StandardCharsets.UTF_8));
            for (Document chunk : chunks) {
                digest.update(chunk.getContent().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Delete stored trials that are no longer in the platform database.
     */
    private List<String> removeDeletedTrials(Set<String> trialIds) {
        List<String> removed = new ArrayList<>();
        if (trialIds.isEmpty()) {
            LOG.warn("No trials returned - skipping removal of deleted trials");
            return removed;
        }

        for (String fileName : repository.findDistinctFileNames()) {
            if (fileName == null || !fileName.startsWith(TRIAL_FILE_PREFIX)) {
                continue;
            }
            String nctId = fileName.substring(TRIAL_FILE_PREFIX.length()).trim();
            if (!trialIds.contains(nctId)) {
                List<DocumentEmbeddingOpenAI> rows = repository.findByFileName(fileName);
                repository.deleteAll(rows);
                removed.add(nctId);
                LOG.info("Removed trial no longer in database: {} ({} chunks)", nctId, rows.size());
            }
        }
        return removed;
    }

    /**
     * Load the trial summary documents and remove summary documents no longer generated.
     */
    private void loadTrialSummary(ClinicalTrailDAO dao, Set<String> trialIds) {
        try {
            List<ClinicalTrialRecord> trials = dao.getAllClinicalTrailRecords().stream()
                    .filter(r -> r.getNctId() != null && trialIds.contains(r.getNctId().trim()))
                    .collect(Collectors.toList());

            Map<String, String> summaries = TrialSummaryBuilder.build(trials);
            LOG.info("Built {} trial summary documents from {} trials", summaries.size(), trials.size());

            int changed = 0;
            for (Map.Entry<String, String> summary : summaries.entrySet()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("filename", summary.getKey());
                List<Document> chunks = splitAndLabel(List.of(new Document(summary.getValue(), metadata)));
                if (replaceSource(summary.getKey(), chunks) != LoadResult.UNCHANGED) {
                    changed++;
                }
            }

            int removed = 0;
            for (String fileName : repository.findDistinctFileNames()) {
                boolean isSummary = fileName != null
                        && (fileName.startsWith(TrialSummaryBuilder.FILE_NAME_PREFIX)
                            || fileName.startsWith(TrialSummaryBuilder.OLD_FILE_NAME_PREFIX));
                if (isSummary && !summaries.containsKey(fileName)) {
                    repository.deleteAll(repository.findByFileName(fileName));
                    removed++;
                }
            }
            LOG.info("Trial summary: {} documents added or updated, {} unchanged, {} removed",
                    changed, summaries.size() - changed, removed);

        } catch (Exception e) {
            // Trial pages are already loaded; a summary failure should not fail the run
            LOG.error("Failed to load trial summary documents", e);
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
