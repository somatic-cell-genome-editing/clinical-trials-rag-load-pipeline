package edu.mcw.scge.service;

import edu.mcw.scge.datamodel.ClinicalTrialRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Builds summary documents listing the Active trials grouped by phase, status, modality,
 * delivery system, vector, editor and route. Individual trial pages cannot answer questions
 * across many trials ("which recruiting trials use AAV?") because the chatbot only sees the
 * closest chunks; one document per group puts the whole answer in a few chunks.
 */
public final class ActiveTrialSummaryBuilder {

    public static final String FILE_NAME_PREFIX = "ACTIVE TRIAL SUMMARY: ";

    private record Dimension(String label, Function<ClinicalTrialRecord, String> field) {}

    private static final List<Dimension> DIMENSIONS = List.of(
            new Dimension("Phase", ClinicalTrialRecord::getPhase),
            new Dimension("Recruitment Status", ClinicalTrialRecord::getStudyStatus),
            new Dimension("Therapeutic Modality", ClinicalTrialRecord::getTherapyType),
            new Dimension("Gene Delivery System", ClinicalTrialRecord::getDeliverySystem),
            new Dimension("Vector Type", ClinicalTrialRecord::getVectorType),
            new Dimension("Editor Type", ClinicalTrialRecord::getEditorType),
            new Dimension("Route of Administration", ClinicalTrialRecord::getRouteOfAdministration)
    );

    // Longest group value used in a file name (file_name column is varchar(255))
    private static final int MAX_VALUE_IN_FILE_NAME = 150;

    private ActiveTrialSummaryBuilder() {}

    /**
     * @return file name -> document text, in a stable order
     */
    public static Map<String, String> build(List<ClinicalTrialRecord> activeTrials) {
        List<ClinicalTrialRecord> trials = new ArrayList<>(activeTrials);
        trials.sort(Comparator.comparing(ClinicalTrialRecord::getNctId));

        Map<String, String> documents = new LinkedHashMap<>();
        StringBuilder overview = new StringBuilder();
        overview.append("Overview of the Active clinical trials tracked by the SCGE Platform Gene Therapy Trial Browser.\n");
        overview.append("Total Active trials: ").append(trials.size()).append("\n");

        for (Dimension dimension : DIMENSIONS) {
            // Group value (case-insensitive) -> trials; a trial with several values appears in each group
            Map<String, List<ClinicalTrialRecord>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            int notSpecified = 0;
            for (ClinicalTrialRecord trial : trials) {
                List<String> values = splitValues(dimension.field().apply(trial));
                if (values.isEmpty()) {
                    notSpecified++;
                }
                for (String value : values) {
                    groups.computeIfAbsent(value, v -> new ArrayList<>()).add(trial);
                }
            }

            overview.append("\nActive trials by ").append(dimension.label()).append(":\n");
            for (Map.Entry<String, List<ClinicalTrialRecord>> group : groups.entrySet()) {
                overview.append("- ").append(group.getKey()).append(": ")
                        .append(group.getValue().size()).append(" trials\n");
            }
            if (notSpecified > 0) {
                overview.append("- Not specified: ").append(notSpecified).append(" trials\n");
            }

            for (Map.Entry<String, List<ClinicalTrialRecord>> group : groups.entrySet()) {
                String value = group.getKey();
                String fileValue = value.length() > MAX_VALUE_IN_FILE_NAME
                        ? value.substring(0, MAX_VALUE_IN_FILE_NAME) : value;
                StringBuilder text = new StringBuilder();
                text.append("Active clinical trials tracked by the SCGE Platform where ")
                        .append(dimension.label()).append(" is ").append(value)
                        .append(" (").append(group.getValue().size()).append(" trials):\n");
                for (ClinicalTrialRecord trial : group.getValue()) {
                    text.append(trialLine(trial)).append("\n");
                }
                documents.put(FILE_NAME_PREFIX + dimension.label() + " = " + fileValue, text.toString());
            }
        }

        // Overview first so it is the first document loaded
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put(FILE_NAME_PREFIX + "Overview", overview.toString());
        ordered.putAll(documents);
        return ordered;
    }

    /**
     * One self-contained line per trial, so any chunk of a long list still makes sense on its own.
     */
    private static String trialLine(ClinicalTrialRecord trial) {
        StringBuilder line = new StringBuilder("- ").append(trial.getNctId());
        appendField(line, ": ", trial.getBriefTitle());
        appendField(line, " | Indication: ", trial.getIndication());
        appendField(line, " | Phase: ", trial.getPhase());
        appendField(line, " | Recruitment status: ", trial.getStudyStatus());
        appendField(line, " | Modality: ", trial.getTherapyType());
        appendField(line, " | Delivery: ", trial.getDeliverySystem());
        appendField(line, " | Vector: ", trial.getVectorType());
        appendField(line, " | Editor: ", trial.getEditorType());
        appendField(line, " | Route: ", trial.getRouteOfAdministration());
        appendField(line, " | Sponsor: ", trial.getSponsor());
        return line.toString();
    }

    private static void appendField(StringBuilder line, String prefix, String value) {
        String clean = clean(value);
        if (!clean.isEmpty()) {
            line.append(prefix).append(clean);
        }
    }

    /**
     * Fields can hold several values ("Phase1, Phase2" or "AAV; LNP").
     */
    private static List<String> splitValues(String value) {
        List<String> values = new ArrayList<>();
        for (String part : clean(value).split("[,;|]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase("null") && !trimmed.equalsIgnoreCase("NA")
                    && !trimmed.equalsIgnoreCase("N/A")) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
