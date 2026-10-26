package com.toadzip.backend.announcement.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.exception.PdfExtractionFailedException;
import com.toadzip.backend.announcement.exception.PdfExtractionUnavailableException;
import com.google.genai.types.Schema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;

class AnnouncementExtractionModelTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    @Test
    void PDF와_프롬프트를_보내고_응답_스키마를_강제한다() {
        RecordingChatModel chatModel = new RecordingChatModel(response("{\"data\":{}}", 900, 120, 1100));
        AnnouncementExtractionModel model = new AnnouncementExtractionModel(provider(chatModel), "추출 지침", "{}");

        model.extract(PDF);

        UserMessage message = (UserMessage) chatModel.prompt.getInstructions().getFirst();
        assertThat(message.getText()).isEqualTo("추출 지침");
        assertThat(message.getMedia()).singleElement()
                .satisfies(media -> assertThat(media.getMimeType().toString()).isEqualTo("application/pdf"));
        assertThat(((StructuredOutputChatOptions) chatModel.prompt.getOptions()).getOutputSchema()).isEqualTo("{}");
    }

    @Test
    void 기본_모델_설정을_유지한_채_스키마만_더한다() {
        RecordingChatModel chatModel = new RecordingChatModel(response("{}", 1, 1, 2));
        chatModel.defaults = StructuredOutputChatOptions.builder().model("configured-model").build();
        AnnouncementExtractionModel model = new AnnouncementExtractionModel(provider(chatModel), "지침", "{}");

        model.extract(PDF);

        assertThat(chatModel.prompt.getOptions().getModel()).isEqualTo("configured-model");
    }

    @Test
    void 응답_JSON과_입력_출력_생각_토큰을_돌려준다() {
        RecordingChatModel chatModel = new RecordingChatModel(response("{\"data\":{}}", 900, 120, 1100));
        AnnouncementExtractionModel model = new AnnouncementExtractionModel(provider(chatModel), "지침", "{}");

        AnnouncementExtractionReply reply = model.extract(PDF);

        assertThat(reply.json()).isEqualTo("{\"data\":{}}");
        assertThat(reply.usage()).isEqualTo(new AnnouncementExtractionReply.Usage(900, 120, 80));
    }

    @Test
    void 채팅_모델이_설정되지_않으면_추출할_수_없다() {
        AnnouncementExtractionModel model = new AnnouncementExtractionModel(provider(null), "지침", "{}");

        assertThatThrownBy(() -> model.extract(PDF)).isInstanceOf(PdfExtractionUnavailableException.class);
    }

    @Test
    void 모델_호출이_실패하면_추출_실패로_알린다() {
        ChatModel failing = prompt -> {
            throw new IllegalStateException("upstream 503");
        };
        AnnouncementExtractionModel model = new AnnouncementExtractionModel(provider(failing), "지침", "{}");

        assertThatThrownBy(() -> model.extract(PDF)).isInstanceOf(PdfExtractionFailedException.class);
    }

    @Test
    void 빈_응답은_추출_실패로_알린다() {
        RecordingChatModel chatModel = new RecordingChatModel(response(" ", 1, 0, 1));
        AnnouncementExtractionModel model = new AnnouncementExtractionModel(provider(chatModel), "지침", "{}");

        assertThatThrownBy(() -> model.extract(PDF)).isInstanceOf(PdfExtractionFailedException.class);
    }

    @Test
    void 응답_스키마는_Gemini_SDK가_읽을_수_있는_형식이다() throws IOException {
        // Gemini는 null 허용을 "type" 배열이 아니라 "nullable"로 받는다. 배열로 쓰면 호출 시점에야 실패한다.
        String schema = new ClassPathResource("announcement/pdf-extraction-schema.json")
                .getContentAsString(StandardCharsets.UTF_8);

        Schema parsed = Schema.fromJson(schema);

        Schema announcement = parsed.properties().orElseThrow().get("data").properties().orElseThrow()
                .get("announcement");
        assertThat(announcement.properties().orElseThrow().get("postedDate").nullable()).contains(true);
    }

    private static ChatResponse response(String text, int promptTokens, int completionTokens, int totalTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens, totalTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private static ObjectProvider<ChatModel> provider(ChatModel chatModel) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (chatModel != null) {
            beans.registerSingleton("chatModel", chatModel);
        }
        return beans.getBeanProvider(ChatModel.class);
    }

    private static final class RecordingChatModel implements ChatModel {
        private final ChatResponse response;
        private ChatOptions defaults = StructuredOutputChatOptions.builder().build();
        private Prompt prompt;

        private RecordingChatModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            return response;
        }

        @Override
        public ChatOptions getOptions() {
            return defaults;
        }
    }
}
