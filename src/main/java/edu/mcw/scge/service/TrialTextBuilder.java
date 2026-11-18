package edu.mcw.scge.service;

import edu.mcw.scge.datamodel.ClinicalTrialExternalLink;
import edu.mcw.scge.datamodel.ClinicalTrialRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the text embedded for a clinical trial straight from its database record. Sections and
 * labels follow the SCGE Platform trial report page, and fields the page does not show (official
 * title, interventions, condition keywords, compound aliases) are added because people ask about
 * trials by those names. Patents and the indication ontology parent terms are left out.
 */
public final class TrialTextBuilder {

    private TrialTextBuilder() {}

    /**
     * @param record          the trial, already formatted with ClinicalTrialRecord.formatRecordValue
     * @param aliases         other names of the drug product (alias table, field "compound")
     * @param fdaDesignations FDA designations (clinical_trial_additional_info, "fda_designation")
     * @param links           external links (clinical_trial_ext_links)
     */
    public static String build(ClinicalTrialRecord record, List<String> aliases, List<String> fdaDesignations,
                               List<ClinicalTrialExternalLink> links) {
        StringBuilder text = new StringBuilder();
        text.append("--- CLINICAL TRIAL: ").append(record.getNctId()).append(" ---\n\n");
        field(text, "Title", record.getBriefTitle());
        text.append("\n");
        field(text, "Official Title", record.getOfficialTitle());
        field(text, "NCTID", record.getNctId());
        field(text, "Description", record.getDescription());
        field(text, "Development Status", record.getDevelopmentStatus());
        field(text, "Indication", record.getIndication());
        field(text, "Disease Ontology Term", record.getIndicationDOID());
        field(text, "Condition Keywords", record.getBrowseConditionTerms());
        field(text, "Drug Product Name", record.getCompoundName());
        field(text, "Drug Product Also Known As", String.join(", ", aliases));
        field(text, "Drug Product Description", record.getCompoundDescription());
        field(text, "Interventions", record.getInterventionName());
        field(text, "Intervention Description", record.getInterventionDescription());
        field(text, "Sponsor", record.getSponsor());
        field(text, "Funder Type", record.getSponsorClass());
        field(text, "Recruitment Status", record.getStudyStatus());
        if (record.getEnrorllmentCount() > 0) {
            String type = clean(record.getEnrollmentType());
            field(text, "Enrollment Count", record.getEnrorllmentCount() + (type.isEmpty() ? "" : " (" + type + ")"));
        }
        field(text, "Results Posted", "true".equalsIgnoreCase(clean(record.getWithHasResults())) ? "Yes" : "Not Available");

        section(text, "Therapy Information");
        field(text, "Target Gene/Variant", record.getTargetGeneOrVariant());
        field(text, "Therapeutic Modality", record.getTherapyType());
        field(text, "Therapy Route", record.getTherapyRoute());
        field(text, "Mechanism of Action", record.getMechanismOfAction());
        field(text, "Route of Administration", record.getRouteOfAdministration());
        field(text, "Drug Product Type", record.getDrugProductType());
        field(text, "Target Tissue/Cell", record.getTargetTissueOrCell());
        field(text, "Gene Delivery System Type", record.getDeliverySystem());
        field(text, "Vector Type", record.getVectorType());
        field(text, "Viral Vector Subtype", record.getViralVectorSubType());
        field(text, "Non-Viral Vector Type", record.getNonViralVectorType());
        field(text, "Editor Type", record.getEditorType());
        field(text, "Regulatory Element", record.getRegulatoryElement());
        field(text, "Dose 1", record.getDose1());
        field(text, "Dose 2", record.getDose2());
        field(text, "Dose 3", record.getDose3());
        field(text, "Dose 4", record.getDose4());
        field(text, "Dose 5", record.getDose5());

        section(text, "Study Record Dates");
        field(text, "Current Phase", record.getPhase());
        field(text, "Submit Date", record.getFirstSubmitDate());
        field(text, "Completion Date", record.getEstimatedCompleteDate());
        field(text, "Last Update", record.getLastUpdatePostDate());

        section(text, "Participation Criteria");
        field(text, "Eligible Age", eligibleAge(record.getElibilityMinAge(), record.getElibilityMaxAge()));
        field(text, "Standard Ages", record.getStandardAge());
        field(text, "Sexes Eligible for Study", record.getEligibilitySex());
        String criteria = record.getEligibilityCriteria();
        if (criteria != null && !criteria.isBlank()) {
            // Kept on separate lines so inclusion and exclusion criteria stay readable
            text.append("Eligibility Criteria:\n").append(criteria.replace("\r", "").trim()).append("\n");
        }

        section(text, "Locations");
        if (record.getNumberOfLocations() > 0) {
            field(text, "No. of Trial Sites", String.valueOf(record.getNumberOfLocations()));
        }
        field(text, "Locations", record.getLocation());

        section(text, "Regulatory Information");
        field(text, "Has US IND", record.getIsFDARegulated());
        field(text, "FDA Designations", String.join(", ", fdaDesignations));
        field(text, "Recent Updates", record.getRecentUpdates());

        Map<String, StringBuilder> linksByType = new LinkedHashMap<>();
        for (ClinicalTrialExternalLink link : links) {
            String name = clean(link.getName());
            String url = clean(link.getLink());
            if (name.isEmpty() && url.isEmpty()) {
                continue;
            }
            String type = clean(link.getType());
            linksByType.computeIfAbsent(type.isEmpty() ? "Other" : type, t -> new StringBuilder())
                    .append("- ").append(name.isEmpty() ? url : name)
                    .append(url.isEmpty() || name.isEmpty() ? "" : " (" + url + ")").append("\n");
        }
        if (!linksByType.isEmpty()) {
            section(text, "Resources/Links");
            linksByType.forEach((type, list) -> text.append(type).append(":\n").append(list));
        }

        return text.toString().trim();
    }

    private static void section(StringBuilder text, String title) {
        text.append("\n=== ").append(title).append(" ===\n");
    }

    private static void field(StringBuilder text, String label, String value) {
        String clean = clean(value);
        if (!clean.isEmpty()) {
            text.append(label).append(": ").append(clean).append("\n");
        }
    }

    private static String eligibleAge(String min, String max) {
        String from = clean(min);
        String to = clean(max);
        if (!from.isEmpty() && !to.isEmpty()) {
            return from + " - " + to;
        }
        if (!from.isEmpty()) {
            return ">=" + from;
        }
        return to.isEmpty() ? "" : "<=" + to;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return clean.equalsIgnoreCase("null") || clean.equalsIgnoreCase("none") ? "" : clean;
    }
}
