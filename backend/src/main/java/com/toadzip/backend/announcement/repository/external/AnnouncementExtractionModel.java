package com.toadzip.backend.announcement.repository.external;

import com.toadzip.backend.announcement.exception.PdfExtractionFailedException;
import com.toadzip.backend.announcement.exception.PdfExtractionUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * 공고문 PDF를 AI 모델에 보내 관리자 보정용 JSON을 받는다.
 * 모델 이름과 생각 수준은 공급자별 설정 파일에 두고, 이 클래스는 공급자와 무관한 ChatModel만 쓴다.
 */
@Component
public class AnnouncementExtractionModel {

    private static final MimeType PDF = MimeType.valueOf("application/pdf");

    private final ObjectProvider<ChatModel> chatModels;
    private final String instructions;
    private final String responseSchema;

    @Autowired
    public AnnouncementExtractionModel(
            ObjectProvider<ChatModel> chatModels,
            @Value("classpath:announcement/pdf-extraction-prompt.txt") Resource instructions,
            @Value("classpath:announcement/pdf-extraction-schema.json") Resource responseSchema
    ) throws IOException {
        this(chatModels, instructions.getContentAsString(StandardCharsets.UTF_8),
                responseSchema.getContentAsString(StandardCharsets.UTF_8));
    }

    AnnouncementExtractionModel(ObjectProvider<ChatModel> chatModels, String instructions, String responseSchema) {
        this.chatModels = chatModels;
        this.instructions = instructions;
        this.responseSchema = responseSchema;
    }

    public AnnouncementExtractionReply extract(byte[] pdf) {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            throw new PdfExtractionUnavailableException();
        }
        return reply(call(chatModel, new Prompt(List.of(message(pdf)), options(chatModel))));
    }

    private ChatResponse call(ChatModel chatModel, Prompt prompt) {
        try {
            return chatModel.call(prompt);
        }
        // 공급자마다 SDK 예외 타입이 달라 호출 경계에서 모두 외부 실패로 바꾼다. 원인은 cause로 남긴다.
        catch (RuntimeException exception) {
            throw new PdfExtractionFailedException("AI 모델 호출에 실패했습니다.", exception);
        }
    }

    private UserMessage message(byte[] pdf) {
        Media document = Media.builder().mimeType(PDF).data(pdf).name("announcement.pdf").build();
        return UserMessage.builder().text(instructions).media(document).build();
    }

    /** 설정 파일의 모델·생각 수준을 그대로 두고, 스키마를 지원하는 모델이면 응답 스키마를 강제한다. */
    private ChatOptions options(ChatModel chatModel) {
        ChatOptions.Builder<?> builder = chatModel.getOptions().mutate();
        if (builder instanceof StructuredOutputChatOptions.Builder<?> structured) {
            structured.outputSchema(responseSchema);
        }
        return builder.build();
    }

    private AnnouncementExtractionReply reply(ChatResponse response) {
        String json = textOf(response);
        if (json == null || json.isBlank()) {
            throw new PdfExtractionFailedException("AI 모델이 빈 응답을 돌려줬습니다.");
        }
        return new AnnouncementExtractionReply(json, usageOf(response.getMetadata().getUsage()));
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private static AnnouncementExtractionReply.Usage usageOf(Usage usage) {
        Integer prompt = usage.getPromptTokens();
        Integer output = usage.getCompletionTokens();
        Integer total = usage.getTotalTokens();
        if (prompt == null || output == null || total == null) {
            return new AnnouncementExtractionReply.Usage(prompt, output, null);
        }
        return new AnnouncementExtractionReply.Usage(prompt, output, Math.max(0, total - prompt - output));
    }
}
