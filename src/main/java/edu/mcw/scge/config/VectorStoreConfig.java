package edu.mcw.scge.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import edu.mcw.scge.repository.DocumentEmbeddingOpenAIRepository;
import edu.mcw.scge.vectorstore.PostgresVectorStoreOpenAI;

import jakarta.annotation.PostConstruct;
import java.util.Map;

@Configuration
public class VectorStoreConfig {

    @Autowired
    private ApplicationContext context;

    @PostConstruct
    public void debugBeans() {
        System.out.println("=== ALL EMBEDDING MODEL BEANS ===");
        Map<String, EmbeddingModel> embeddingModels = context.getBeansOfType(EmbeddingModel.class);
        for (Map.Entry<String, EmbeddingModel> entry : embeddingModels.entrySet()) {
            System.out.println("Bean name: '" + entry.getKey() + "', Class: " + entry.getValue().getClass().getSimpleName());
        }
        System.out.println("===================================");
    }

    @Bean
    @Qualifier("openaiVectorStore")
    VectorStore openaiVectorStore(DocumentEmbeddingOpenAIRepository repository) {
        System.out.println("Looking for OpenAI embedding model...");

        // Get all embedding models and find the OpenAI one
        Map<String, EmbeddingModel> embeddingModels = context.getBeansOfType(EmbeddingModel.class);
        EmbeddingModel openAiModel = null;

        for (Map.Entry<String, EmbeddingModel> entry : embeddingModels.entrySet()) {
            String beanName = entry.getKey();
            EmbeddingModel model = entry.getValue();
            System.out.println("Checking bean: '" + beanName + "', Class: " + model.getClass().getSimpleName());

            // Look for OpenAI embedding model by class name
            if (model.getClass().getSimpleName().toLowerCase().contains("openai")) {
                openAiModel = model;
                System.out.println("Found OpenAI embedding model: " + beanName);
                break;
            }
        }

        if (openAiModel == null) {
            throw new RuntimeException("Could not find OpenAI embedding model! Available beans: " + embeddingModels.keySet());
        }

        return new PostgresVectorStoreOpenAI(repository, openAiModel);
    }
}
