package edu.mcw.scge.controller;

import edu.mcw.scge.datamodel.Alias;
import edu.mcw.scge.datamodel.ClinicalTrialAdditionalInfo;
import edu.mcw.scge.datamodel.ClinicalTrialRecord;
import edu.mcw.scge.reader.UrlDocumentReader;
import edu.mcw.scge.service.TrialSummaryBuilder;
import edu.mcw.scge.service.TrialTextBuilder;
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
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class UrlController {
    private static final Logger LOG = LoggerFactory.getLogger(UrlController.class);
    private static final String TRIAL_FILE_PREFIX = "CLINICAL TRIAL: ";
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf", "docx", "doc", "md", "txt");
    // Documents with less extractable text than this are skipped as scanned images
    private static final int MIN_DOCUMENT_TEXT = 500;

    private final VectorStore openaiVectorStore;
    private final DocumentPreprocessor preprocessor;
    private final DocumentEmbeddingOpenAIRepository repository;
    private final String embeddingModelName;
    private final String embeddingDimensions;
    private final String platformBaseUrl;
    private final String pagesFile;
    private final String documentsFile;
    private final String documentsBaseUrl;
    private final String documentsDir;

    public UrlController(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                         DocumentPreprocessor preprocessor,
                         DocumentEmbeddingOpenAIRepository repository,
                         @Value("${spring.ai.openai.embedding.options.model:text-embedding-ada-002}") String embeddingModelName,
                         @Value("${spring.ai.openai.embedding.options.dimensions:}") String embeddingDimensions,
                         @Value("${pipeline.platform-base-url:}") String platformBaseUrl,
                         @Value("${pipeline.pages-file:}") String pagesFile,
                         @Value("${pipeline.documents-file:}") String documentsFile,
                         @Value("${pipeline.documents-base-url:https://scge.mcw.edu/assistant/docs}") String documentsBaseUrl,
                         @Value("${pipeline.documents-dir:}") String documentsDir){
        this.openaiVectorStore = openaiVectorStore;
        this.preprocessor = preprocessor;
        this.repository = repository;
        this.embeddingModelName = embeddingModelName;
        this.embeddingDimensions = embeddingDimensions;
        this.platformBaseUrl = platformBaseUrl.isBlank() ? defaultPlatformBaseUrl() : platformBaseUrl.replaceAll("/+$", "");
        LOG.info("SCGE Platform pages are read from {}", this.platformBaseUrl);
        this.pagesFile = pagesFile;
        this.documentsFile = documentsFile;
        this.documentsBaseUrl = documentsBaseUrl.replaceAll("/+$", "");
        this.documentsDir = documentsDir;
    }

    /**
     * SCGE site whose platform pages are embedded: production on the production server,
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
            // Only Active records are published in the SCGE Trial Browser
            List<String> nctIds = dao.getNctIdsByRecordStatus("Active");
            LOG.info("Retrieved {} Active NCT IDs from scgeplatform database", nctIds.size());

            Set<String> trialIds = new TreeSet<>();
            for (String nctId : nctIds) {
                if (nctId == null || nctId.trim().isEmpty()) {
                    LOG.warn("Skipping empty nctId");
                    continue;
                }
                trialIds.add(nctId.trim());
            }

            // Trial text is built from the database records, not the report web page
            Map<String, ClinicalTrialRecord> records = new HashMap<>();
            for (ClinicalTrialRecord record : dao.getAllClinicalTrailRecords()) {
                if (record.getNctId() != null && trialIds.contains(record.getNctId().trim())) {
                    records.put(record.getNctId().trim(), record.formatRecordValue(record));
                }
            }

            List<String> added = new ArrayList<>();
            List<String> updated = new ArrayList<>();
            List<String> unchanged = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            for (String nctId : trialIds) {
                try {
                    LOG.info("Processing trial: {}", nctId);
                    ClinicalTrialRecord record = records.get(nctId);
                    if (record == null) {
                        failed.add(nctId);
                        LOG.error("No record found for trial: {} (existing entries kept)", nctId);
                        continue;
                    }

                    List<String> aliases = dao.getAliases(nctId, "compound").stream()
                            .map(Alias::getAlias).filter(Objects::nonNull).collect(Collectors.toList());
                    List<String> fdaDesignations = dao.getAdditionalInfo(nctId, "fda_designation").stream()
                            .map(ClinicalTrialAdditionalInfo::getPropertyValue).filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    String text = TrialTextBuilder.build(record, aliases, fdaDesignations,
                            dao.getExtLinksByNctIdSorted(nctId));

                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("filename", TRIAL_FILE_PREFIX + nctId);
                    List<Document> chunks = splitAndLabel(List.of(new Document(text, metadata)));

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
            loadTrialSummary(new ArrayList<>(records.values()));

            LOG.info("Clinical trials processing complete. Active trials: {}, Added: {}, Updated: {}, Unchanged: {}, Failed: {}, Removed (no longer Active): {}",
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
     * Embed the public SCGE pages listed in site-pages.txt (or pipeline.pages-file). Pages are
     * stored as "name:url" so the chatbot cites them by URL.
     *
     * @return the file names of the listed pages, or null if the list could not be read
     */
    public Set<String> loadSitePages() {
        List<String[]> pages;
        try {
            pages = readSourceList(pagesFile, "site-pages.txt");
        } catch (Exception e) {
            LOG.error("Could not read the page list - site pages skipped", e);
            return null;
        }
        LOG.info("Loading {} site pages", pages.size());

        Set<String> listed = new HashSet<>();
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String[] page : pages) {
            String url = page[0];
            String fileName = page[1] + ":" + url;
            listed.add(fileName);
            try {
                List<Document> chunks = buildChunks(url, fileName);
                if (chunks == null) {
                    failed.add(url);
                    LOG.error("Failed to load page: {} (existing entries kept)", url);
                    continue;
                }
                switch (replaceSource(fileName, chunks)) {
                    case ADDED -> added.add(url);
                    case UPDATED -> updated.add(url);
                    case UNCHANGED -> unchanged.add(url);
                }
            } catch (Exception e) {
                failed.add(url);
                LOG.error("Exception loading page: {} (existing entries kept)", url, e);
            }
        }

        LOG.info("Site pages complete. Listed: {}, Added: {}, Updated: {}, Unchanged: {}, Failed: {}",
                pages.size(), added.size(), updated.size(), unchanged.size(), failed.size());
        LOG.info("Failed pages: {}", failed);
        return listed;
    }

    /**
     * Lines of "URL | name" from the given file, or from the classpath resource when no file is
     * configured. {platform} is this server's SCGE Platform, {docs} is pipeline.documents-base-url.
     */
    private List<String[]> readSourceList(String file, String resource) throws IOException {
        List<String> lines;
        if (file.isBlank()) {
            try (InputStream in = new ClassPathResource(resource).getInputStream()) {
                lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
        } else {
            lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        }

        String platform = platformBaseUrl + "/platform";
        List<String[]> pages = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int bar = trimmed.indexOf('|');
            String url = (bar >= 0 ? trimmed.substring(0, bar) : trimmed).trim()
                    .replace("{platform}", platform).replace("{docs}", documentsBaseUrl);
            String name = bar >= 0 ? trimmed.substring(bar + 1).trim() : "";
            if (name.isEmpty() || name.contains(":http")) {
                name = url;
            }
            pages.add(new String[]{url, name});
        }
        return pages;
    }


    /**
     * Download and embed the public PDF documents listed in site-documents.txt (or
     * pipeline.documents-file). Byte-identical files are embedded once, under the first name
     * listed. Documents are stored as "name:url" so the chatbot cites them by URL.
     *
     * @return the file names of the listed documents, or null if the list could not be read
     */
    public Set<String> loadWebDocuments() {
        List<String[]> documents;
        try {
            documents = readSourceList(documentsFile, "site-documents.txt");
        } catch (Exception e) {
            LOG.error("Could not read the document list - documents skipped", e);
            return null;
        }
        LOG.info("Loading {} documents", documents.size());

        Set<String> listed = new HashSet<>();
        Map<String, String> firstNameByFileHash = new HashMap<>();
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        for (String[] document : documents) {
            String url = document[0];
            String fileName = document[1] + ":" + url;
            try {
                HttpResponse<byte[]> response = http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200 || response.body().length == 0) {
                    listed.add(fileName);   // keep what is stored until the file is back
                    failed.add(document[1] + " (HTTP " + response.statusCode() + ")");
                    continue;
                }

                String fileHash = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(response.body()));
                String firstName = firstNameByFileHash.putIfAbsent(fileHash, document[1]);
                if (firstName != null) {
                    duplicates.add(document[1] + " = " + firstName);
                    continue;
                }
                listed.add(fileName);

                List<Document> chunks = buildDocumentChunks(new ByteArrayResource(response.body()), fileName);
                if (chunks == null) {
                    failed.add(document[1]);
                    continue;
                }
                switch (replaceSource(fileName, chunks)) {
                    case ADDED -> added.add(document[1]);
                    case UPDATED -> updated.add(document[1]);
                    case UNCHANGED -> unchanged.add(document[1]);
                }
            } catch (Exception e) {
                listed.add(fileName);
                failed.add(document[1]);
                LOG.error("Exception loading document: {} (existing entries kept)", url, e);
            }
        }

        LOG.info("Documents complete. Listed: {}, Added: {}, Updated: {}, Unchanged: {}, Duplicates skipped: {}, Failed: {}",
                documents.size(), added.size(), updated.size(), unchanged.size(), duplicates.size(), failed.size());
        LOG.info("Duplicate documents (embedded under the first name): {}", duplicates);
        LOG.info("Failed documents: {}", failed);
        return listed;
    }

    /**
     * Remove pages and documents this pipeline loaded earlier that are no longer in either list.
     * Skipped when a list could not be read. Pages added by hand with Process URL have no
     * content hash and are left alone.
     */
    public void removeUnlistedWebSources(Set<String> listedPages, Set<String> listedDocuments) {
        if (listedPages == null || listedDocuments == null || listedPages.isEmpty()) {
            LOG.warn("A page or document list was not read - skipping removal of unlisted pages and documents");
            return;
        }
        List<String> removed = new ArrayList<>();
        for (String fileName : repository.findDistinctFileNames()) {
            if (fileName != null && fileName.contains(":http")
                    && !listedPages.contains(fileName) && !listedDocuments.contains(fileName)
                    && isLoadedByPipeline(fileName)) {
                repository.deleteAll(repository.findByFileName(fileName));
                removed.add(fileName);
            }
        }
        LOG.info("Removed {} pages/documents no longer listed: {}", removed.size(), removed);
    }

    /**
     * Embed the documents (PDF, Word, Markdown, text) in pipeline.documents-dir and remove
     * documents this pipeline loaded earlier whose file is gone. Documents are stored under
     * their file name, which the chatbot links as /docs/<name> (name.md links to name.pdf).
     */
    public void loadDocuments() {
        if (documentsDir.isBlank()) {
            LOG.info("pipeline.documents-dir is not set - documents skipped");
            return;
        }
        Path dir = Path.of(documentsDir);
        if (!Files.isDirectory(dir)) {
            LOG.error("Documents folder not found: {} - documents skipped", dir);
            return;
        }

        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(Files::isRegularFile)
                    .filter(f -> DOCUMENT_EXTENSIONS.contains(extension(f)))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.error("Could not list documents folder: {} - documents skipped", dir, e);
            return;
        }

        // name.md is the text version of name.pdf - embed the .md only
        Set<String> markdownBases = files.stream().filter(f -> extension(f).equals("md"))
                .map(UrlController::baseName).collect(Collectors.toSet());
        files.removeIf(f -> extension(f).equals("pdf") && markdownBases.contains(baseName(f)));

        if (files.isEmpty()) {
            LOG.warn("No documents in {} - documents skipped (nothing removed)", dir);
            return;
        }
        LOG.info("Loading {} documents from {}", files.size(), dir);

        Set<String> present = new HashSet<>();
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            present.add(fileName);
            try {
                List<Document> chunks = buildDocumentChunks(new FileSystemResource(file), fileName);
                if (chunks == null) {
                    failed.add(fileName);
                    continue;
                }
                switch (replaceSource(fileName, chunks)) {
                    case ADDED -> added.add(fileName);
                    case UPDATED -> updated.add(fileName);
                    case UNCHANGED -> unchanged.add(fileName);
                }
            } catch (Exception e) {
                failed.add(fileName);
                LOG.error("Exception loading document: {} (existing entries kept)", fileName, e);
            }
        }

        // Documents uploaded through the Curation page have no content hash and are left alone
        List<String> removed = new ArrayList<>();
        for (String fileName : repository.findDistinctFileNames()) {
            if (fileName != null && !fileName.contains(":http") && !fileName.startsWith(TRIAL_FILE_PREFIX)
                    && !fileName.startsWith(TrialSummaryBuilder.FILE_NAME_PREFIX)
                    && !fileName.startsWith(TrialSummaryBuilder.OLD_FILE_NAME_PREFIX)
                    && !present.contains(fileName) && isLoadedByPipeline(fileName)) {
                repository.deleteAll(repository.findByFileName(fileName));
                removed.add(fileName);
            }
        }

        LOG.info("Documents complete. Files: {}, Added: {}, Updated: {}, Unchanged: {}, Failed: {}, Removed: {}",
                files.size(), added.size(), updated.size(), unchanged.size(), failed.size(), removed.size());
        LOG.info("Failed documents: {}", failed);
        LOG.info("Removed documents: {}", removed);
    }

    private List<Document> buildDocumentChunks(Resource source, String fileName) {
        try {
            List<Document> documents = new TikaDocumentReader(source).get().stream()
                    .map(doc -> {
                        Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                        metadata.put("filename", fileName);
                        return new Document(doc.getContent(), metadata);
                    })
                    .collect(Collectors.toList());

            // Scanned PDFs (page images, e.g. some redacted documents) have no text layer
            int textLength = documents.stream().mapToInt(doc -> doc.getContent().trim().length()).sum();
            if (textLength < MIN_DOCUMENT_TEXT) {
                LOG.warn("Document has almost no extractable text ({} chars) - probably a scanned PDF, skipped: {}",
                        textLength, fileName);
                return null;
            }

            // Same quality filter as uploads through the chat app
            List<Document> chunks = splitAndLabel(documents).stream()
                    .filter(chunk -> preprocessor.isQualityChunk(chunk.getContent()))
                    .collect(Collectors.toList());
            if (chunks.isEmpty()) {
                LOG.error("No usable text in document: {}", fileName);
                return null;
            }
            return chunks;
        } catch (Exception e) {
            LOG.error("Failed to read document: {}", fileName, e);
            return null;
        }
    }

    /**
     * Rows saved by this pipeline carry a content hash; manual uploads do not.
     */
    private boolean isLoadedByPipeline(String fileName) {
        return repository.findByFileName(fileName).stream().anyMatch(row -> row.getContentHash() != null);
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * Fetch a page and turn it into labeled chunks. Returns null if the page could not be read.
     */
    private List<Document> buildChunks(String urlString, String fileName) {
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
                        mutableMetadata.put("filename", fileName);
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
     * Delete stored trials that are no longer Active (only Active trials are published).
     */
    private List<String> removeDeletedTrials(Set<String> trialIds) {
        List<String> removed = new ArrayList<>();
        if (trialIds.isEmpty()) {
            LOG.warn("No Active trials returned - skipping removal of trials that are no longer Active");
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
                LOG.info("Removed trial no longer Active: {} ({} chunks)", nctId, rows.size());
            }
        }
        return removed;
    }

    /**
     * Load the trial summary documents and remove summary documents no longer generated.
     */
    private void loadTrialSummary(List<ClinicalTrialRecord> trials) {
        try {
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

}
