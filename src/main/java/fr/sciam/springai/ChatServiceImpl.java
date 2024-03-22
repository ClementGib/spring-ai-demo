package fr.sciam.springai;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiImageClient;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Service
public class ChatServiceImpl implements ChatService {
    private final OpenAiChatClient chatClient;

    private final OpenAiImageClient imageClient;

    private final Resource systemPrompt;

    private final VectorStore vectorStore;

    private final Resource dataPdf;

    private final JdbcTemplate jdbcTemplate;

    public ChatServiceImpl(
            OpenAiChatClient chatClient,
            OpenAiImageClient imageClient,
            @Value("classpath:/prompts/system-qa-rag2.st") Resource systemPrompt,
            VectorStore vectorStore,
            @Value("classpath:/data/rapport-commission-ia.pdf") Resource dataPdf,
            JdbcTemplate jdbcTemplate) {
        this.chatClient = chatClient;
        this.imageClient = imageClient;
        this.systemPrompt = systemPrompt;
        this.vectorStore = vectorStore;
        this.dataPdf = dataPdf;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String chat(String message) {
        var prompt = getPrompt(message);
        return chatClient.call(prompt).getResult().getOutput().getContent();
    }

    @Override
    public Flux<String> chatWithStream(String message) {
        var prompt = getPrompt(message);
        return chatClient.stream(prompt)
                .map(response -> response.getResult().getOutput().getContent());
    }

    @Override
    public String generateImage(String message) {
        var prompt = new ImagePrompt(message, OpenAiImageOptions.builder()
                .withHeight(1024)
                .withWidth(1024)
                .withN(1)
                .build());
        return imageClient.call(prompt).getResult().getOutput().getUrl();
    }

    @Override
    public void ingestData() {

        var dataLink = extractData();

        var docPdf = extractData2();

        jdbcTemplate.update("delete from vector_store");

        var tokenTextSplitter = new TokenTextSplitter();

        vectorStore.accept(tokenTextSplitter.apply(dataLink));

        vectorStore.accept(tokenTextSplitter.apply(docPdf));
    }

    private Prompt getPrompt(String message) {

        // Retrieve similar chunks from the vector database
        var similarity = vectorStore.similaritySearch(
                SearchRequest.query("")
                        .withQuery(message)
                        .withSimilarityThreshold(0.1)
                        .withTopK(5));

        var systemMessage = new SystemPromptTemplate(systemPrompt)
                .createMessage(Map.of("question", message, "documents", similarity, "functions", "paymentStatus"));

        var userMessage = new UserMessage(message);

        return new Prompt(List.of(systemMessage, userMessage));
    }

    private List<Document> extractData() {

        // Get data and Extract text from page html
        var dataUrl = "https://rickenbazolo.com/";
        var tikaReader = new TikaDocumentReader(dataUrl);

        return tikaReader.get();
    }

    private List<Document> extractData2() {

        // Get data and Extract text from pdf
        var docs = new PagePdfDocumentReader(dataPdf,
                PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0)
                        .withPageExtractedTextFormatter(
                                ExtractedTextFormatter.builder()
                                        .withNumberOfTopTextLinesToDelete(0)
                                        .build())
                        .withPagesPerDocument(1)
                        .build());

        return docs.get();
    }

}
