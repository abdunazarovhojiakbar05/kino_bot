package dastur.uz.bot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;


import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class Bot extends TelegramLongPollingBot {

    private final MovieRepository movieRepository;
    private static final int PAGE_SIZE = 10;
    private final UserRepository userRepository;

    public Bot(MovieRepository movieRepository, UserRepository userRepository) {
        this.movieRepository = movieRepository;
        this.userRepository = userRepository;
    }

    @Value("${telegram.bot.protect-content:false}")
    private boolean isContentProtected;

    @Value("${telegram.bot.name}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;

    @Override
    public String getBotUsername() {
        return this.botUsername;
    }

    @Override
    public String getBotToken() {
        return this.botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {



        if (update.hasChannelPost()) {
            Message post = update.getChannelPost();

            String caption = "";
            if (post.hasVideo() && post.getCaption() != null) {
                caption = post.getCaption().trim().toLowerCase();
            } else if (post.hasText()) {
                caption = post.getText().trim().toLowerCase();
            }

            if (!caption.isEmpty() && (caption.contains("reklama") || caption.contains("#reklama"))) {
                forwardReklamaToAllUsers(post.getChatId(), post.getMessageId());
                return;
            }

            if (post.hasVideo() && post.getCaption() != null) {
                String name = post.getCaption().trim().toLowerCase();
                String fileId = post.getVideo().getFileId();
                Movie movie = new Movie();
                movie.setName(name);
                movie.setFileId(fileId);
                movieRepository.save(movie);
            }
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText().trim();
            Long chatId = update.getMessage().getChatId();


            if (text.equals("/start")) {

                User user = new User();
                user.setChat_id(chatId);
                userRepository.save(user);

                sendMessage(chatId, "🎬 Kino botga xush kelibsiz!\n\n" +
                        "Kino nomini yozing va men topib beraman.\n\n" +
                        "Misol: Avatar\n\n" +
                        "/list — barcha kinolar ro'yxati");
                return;
            }

            if (text.equals("/list")) {
                sendMovieList(chatId, 0);
                return;
            }

            Optional<Movie> movie = movieRepository.findByNameIgnoreCase(text);
            if (movie.isPresent()) {
                sendVideo(chatId, movie.get());
            } else {
                sendMessage(chatId, "❌ Kino topilmadi: " + text);
            }
            return;
        }

        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();
            Long chatId = update.getCallbackQuery().getMessage().getChatId();

            if (data.startsWith("movie_")) {
                Long movieId = Long.parseLong(data.replace("movie_", ""));
                Optional<Movie> movie = movieRepository.findById(movieId);
                if (movie.isPresent()) {
                    sendVideo(chatId, movie.get());
                } else {
                    sendMessage(chatId, "❌ Kino topilmadi!");
                }

            } else if (data.startsWith("page_")) {
                int page = Integer.parseInt(data.replace("page_", ""));
                sendMovieList(chatId, page);
            }
        }
    }

    private void forwardReklamaToAllUsers(Long fromChatId, Integer messageId) {
        List<User> users = userRepository.findAll();

        for (User user : users) {
            if (user.getChat_id() == null) continue;

            try {
                ForwardMessage forwardMessage = ForwardMessage.builder()
                        .chatId(user.getChat_id())
                        .fromChatId(fromChatId)
                        .messageId(messageId)
                        .build();

                execute(forwardMessage);
            } catch (TelegramApiException e) {
                System.out.println("Reklama yetkazilmadi (bloklangan): " + user.getChat_id());
            }
        }
    }

    private void sendMovieList(Long chatId, int page) {
        Page<Movie> moviePage = movieRepository.findAll(PageRequest.of(page, PAGE_SIZE));
        List<Movie> movies = moviePage.getContent();

        if (movies.isEmpty()) {
            sendMessage(chatId, "❌ Hali kino qo'shilmagan!");
            return;
        }

        StringBuilder text = new StringBuilder("🎬 *Kinolar ro'yxati:*\n\n");
        for (Movie movie : movies) {
            text.append("🎥 `").append(movie.getId()).append("` — ").append(movie.getName()).append("\n");
        }

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        for (int i = 0; i < movies.size(); i++) {
            row.add(InlineKeyboardButton.builder()
                    .text(String.valueOf(movies.get(i).getId()))
                    .callbackData("movie_" + movies.get(i).getId())
                    .build());

            if (row.size() == 5) {
                rows.add(new ArrayList<>(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) rows.add(row);

        List<InlineKeyboardButton> navRow = new ArrayList<>();
        if (page > 0) {
            navRow.add(InlineKeyboardButton.builder()
                    .text("◀️")
                    .callbackData("page_" + (page - 1))
                    .build());
        }
        if (moviePage.hasNext()) {
            navRow.add(InlineKeyboardButton.builder()
                    .text("▶️")
                    .callbackData("page_" + (page + 1))
                    .build());
        }
        if (!navRow.isEmpty()) rows.add(navRow);

        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text.toString())
                    .parseMode("Markdown")
                    .protectContent(isContentProtected)
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendVideo(Long chatId, Movie movie) {
        SendVideo sendVideo = new SendVideo();
        sendVideo.setChatId(chatId);
        sendVideo.setVideo(new InputFile(movie.getFileId()));
        sendVideo.setCaption("🎬 " + movie.getName());
        sendVideo.setProtectContent(isContentProtected);
        try {
            execute(sendVideo);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(Long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .protectContent(isContentProtected)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}