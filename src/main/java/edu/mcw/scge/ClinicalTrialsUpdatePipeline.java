package edu.mcw.scge;

import edu.mcw.scge.controller.UrlController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ClinicalTrialsUpdatePipeline implements CommandLineRunner {
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalTrialsUpdatePipeline.class);

    @Autowired
    private UrlController urlController;

    public static void main(String[] args) {
        // Debug: Print SPRING_CONFIG system property BEFORE Spring Boot starts
        String springConfig = System.getProperty("SPRING_CONFIG");
        System.out.println("DEBUG - SPRING_CONFIG at startup: " + springConfig);
        System.out.println("DEBUG - All JVM args: " + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());

        LOG.info("=== Clinical Trials Update Pipeline Starting ===");
        SpringApplication app = new SpringApplication(ClinicalTrialsUpdatePipeline.class);
        app.setWebApplicationType(WebApplicationType.NONE);  // NOT a web application!
        System.exit(SpringApplication.exit(app.run(args)));
    }

    @Override
    public void run(String... args) throws Exception {
        try {
            LOG.info("Starting clinical trials update process...");
            long startTime = System.currentTimeMillis();

            // Run the clinical trials loading process
            urlController.loadClinicalTrials();

            long endTime = System.currentTimeMillis();
            long elapsedSeconds = (endTime - startTime) / 1000;
            long minutes = elapsedSeconds / 60;
            long seconds = elapsedSeconds % 60;

            LOG.info("=== Clinical Trials Update Pipeline Complete ===");
            LOG.info("Total execution time: {}m {}s", minutes, seconds);

        } catch (Exception e) {
            LOG.error("=== Clinical Trials Update Pipeline FAILED ===", e);
            throw e; // Re-throw to set proper exit code
        }
    }
}
