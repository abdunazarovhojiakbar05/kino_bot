package dastur.uz.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ForwardMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class Bot extends TelegramLongPollingBot {


    private static final int    PAGE_SIZE        = 10;
    private static final String REKLAMA_KEYWORD  = "reklama";


    private final MovieRepository movieRepository;
    private final UserRepository  userRepository;

    @Value("${telegram.bot.protect-content:false}")
    private boolean isContentProtected;

    @Value("${telegram.bot.name}")
    private String botUsername;

    @Value("${telegram.bot.token}")
    private String botToken;

    public Bot(MovieRepository movieRepository, UserRepository userRepository) {
        this.movieRepository = movieRepository;
        this.userRepository  = userRepository;
    }


    @Override public String getBotUsername() { return botUsername; }
    @Override public String getBotToken()    { return botToken;    }


    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasChannelPost()) {
                handleChannelPost(update.getChannelPost());
            } else if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update.getMessage());
            } else if (update.hasCallbackQuery()
                    && update.getCallbackQuery().getMessage() != null) {
                handleCallback(
                        update.getCallbackQuery().getData(),
                        update.getCallbackQuery().getMessage().getChatId()
                );
            }
        } catch (Exception e) {
            log.error("onUpdateReceived: kutilmagan xato", e);
        }
    }


    private void handleChannelPost(Message post) {
        String caption = extractCaption(post);

        if (isReklama(caption)) {
            forwardReklamaToAllUsers(post.getChatId(), post.getMessageId());
            return;
        }

        if (post.hasVideo() && post.getCaption() != null) {
            saveMovieFromPost(post);
        }
    }


    private String extractCaption(Message post) {
        if (post.getCaption() != null) return post.getCaption().trim();
        if (post.hasText())            return post.getText().trim();
        return "";
    }

    private boolean isReklama(String text) {
        String lower = text.toLowerCase();
        return lower.contains(REKLAMA_KEYWORD) || lower.contains("#" + REKLAMA_KEYWORD);
    }


    private void saveMovieFromPost(Message post) {
        String[] parts = post.getCaption().trim().split("\\|");
        if (parts.length == 0 || parts[0].isBlank()) {
            log.warn("saveMovieFromPost: caption bo'sh yoki noto'g'ri format");
            return;
        }

        Movie movie = new Movie();
        movie.setName(parts[0].trim().toLowerCase());
        movie.setFileId(post.getVideo().getFileId());

        if (parts.length > 1 && !parts[1].isBlank()) {
            movie.setCode(parts[1].trim());
        }
        if (parts.length > 2 && !parts[2].isBlank()) {
            Set<Genre> genres = parseGenres(parts[2]);
            movie.setGenres(genres);
        }

        movieRepository.save(movie);
        log.info("Kino saqlandi: '{}' (kod={})", movie.getName(), movie.getCode());
    }

    private Set<Genre> parseGenres(String raw) {
        Set<Genre> genres = new HashSet<>();
        for (String g : raw.split(",")) {
            try {
                genres.add(Genre.valueOf(g.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Noto'g'ri janr e'tiborsiz qoldirildi: '{}'", g.trim());
            }
        }
        return genres;
    }


    private void handleMessage(Message message) {
        String text   = message.getText().trim();
        Long   chatId = message.getChatId();

        if (text.equals("/start")) {
            registerUserIfAbsent(chatId);
            sendStartMenu(chatId);
            return;
        }
        if (text.equals("/list")) {
            sendMovieList(chatId, 0, null);
            return;
        }

        try {
            long id = Long.parseLong(text);
            Optional<Movie> byId = movieRepository.findById(id);
            if (byId.isPresent()) {
                sendVideo(chatId, byId.get());
                return;
            }
        } catch (NumberFormatException ignored) { /* matn — davom etamiz */ }

        Optional<Movie> byCode = movieRepository.findByCodeIgnoreCase(text);
        if (byCode.isPresent()) {
            sendVideo(chatId, byCode.get());
            return;
        }

        Optional<Movie> byName = movieRepository.findByNameIgnoreCase(text.toLowerCase());
        if (byName.isPresent()) {
            sendVideo(chatId, byName.get());
            return;
        }

        sendMessage(chatId,
                "❌ Kino topilmadi: *" + escapeMarkdown(text) + "*\n\n" +
                        "Kino nomini, kodini yoki ID raqamini to'g'ri kiriting.");
    }

    private void registerUserIfAbsent(Long chatId) {
        if (!userRepository.existsByChatId(chatId)) {
            User user = new User();
            user.setChat_id(chatId);
            userRepository.save(user);
            log.info("Yangi foydalanuvchi ro'yxatdan o'tdi: {}", chatId);
        }
    }


    private void handleCallback(String data, Long chatId) {
        if (data == null || data.isBlank()) return;

        switch (data) {
            case "menu"     -> sendStartMenu(chatId);
            case "genres"   -> sendGenreMenu(chatId);
            case "all_list" -> sendMovieList(chatId, 0, null);
            default         -> handleDynamicCallback(data, chatId);
        }
    }

    private void handleDynamicCallback(String data, Long chatId) {

        if (data.startsWith("movie_")) {
            handleMovieCallback(data, chatId);
            return;
        }

        if (data.startsWith("genre_")) {
            String[] parts = data.split("_", 2);
            if (parts.length == 2) {
                parseGenreAndShowList(parts[1], chatId, 0);
            }
            return;
        }


        if (data.startsWith("page_")) {
            handlePageCallback(data, chatId);
            return;
        }

        log.warn("Noma'lum callback data: '{}'", data);
    }

    private void handleMovieCallback(String data, Long chatId) {
        String idStr = data.substring("movie_".length());
        try {
            long movieId = Long.parseLong(idStr);
            movieRepository.findById(movieId).ifPresentOrElse(
                    movie -> sendVideo(chatId, movie),
                    ()    -> sendMessage(chatId, "❌ Kino topilmadi!")
            );
        } catch (NumberFormatException e) {
            log.warn("handleMovieCallback: noto'g'ri id '{}'", idStr);
            sendMessage(chatId, "❌ Noto'g'ri so'rov!");
        }
    }

    private void handlePageCallback(String data, Long chatId) {

        String body = data.substring("page_".length());
        String[] parts = body.split("_", 2);

        if (parts.length == 1) {
            parsePageNumber(parts[0], chatId, null);
        } else {
            String genreName = parts[0];
            try {
                Genre genre = Genre.valueOf(genreName.toUpperCase());
                parsePageNumber(parts[1], chatId, genre);
            } catch (IllegalArgumentException e) {
                log.warn("handlePageCallback: noto'g'ri janr '{}'", genreName);
                sendMessage(chatId, "❌ Noto'g'ri janr!");
            }
        }
    }

    private void parsePageNumber(String raw, Long chatId, Genre genre) {
        try {
            int page = Integer.parseInt(raw);
            if (page < 0) page = 0;
            sendMovieList(chatId, page, genre);
        } catch (NumberFormatException e) {
            log.warn("parsePageNumber: noto'g'ri sahifa '{}'", raw);
            sendMessage(chatId, "❌ Noto'g'ri sahifa!");
        }
    }

    private void parseGenreAndShowList(String genreName, Long chatId, int page) {
        try {
            Genre genre = Genre.valueOf(genreName.toUpperCase());
            sendMovieList(chatId, page, genre);
        } catch (IllegalArgumentException e) {
            log.warn("parseGenreAndShowList: noto'g'ri janr '{}'", genreName);
            sendMessage(chatId, "❌ Noto'g'ri janr!");
        }
    }


    private void sendStartMenu(Long chatId) {
        List<List<InlineKeyboardButton>> rows = List.of(
                List.of(btn("🎬 Barcha kinolar", "all_list")),
                List.of(btn("🎭 Janrlar bo'yicha", "genres"))
        );

        executeSafe(SendMessage.builder()
                .chatId(chatId)
                .text("🎬 *Kino botga xush kelibsiz!*\n\n"
                        + "Kino *nomi*, *kodi* yoki *ID raqami*ni kiriting.\n"
                        + "Yoki quyidagi tugmalardan foydalaning:")
                .parseMode("Markdown")
                .protectContent(isContentProtected)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                .build());
    }

    private void sendGenreMenu(Long chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        for (Genre genre : Genre.values()) {
            row.add(btn(genreEmoji(genre) + " " + genre.name(), "genre_" + genre.name()));
            if (row.size() == 2) {
                rows.add(new ArrayList<>(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) rows.add(row);
        rows.add(List.of(btn("🔙 Orqaga", "menu")));

        executeSafe(SendMessage.builder()
                .chatId(chatId)
                .text("🎭 *Janrni tanlang:*")
                .parseMode("Markdown")
                .protectContent(isContentProtected)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                .build());
    }


    private void sendMovieList(Long chatId, int page, Genre genre) {
        Page<Movie> moviePage = fetchPage(page, genre);

        if (moviePage.isEmpty()) {
            sendMessage(chatId, genre != null
                    ? "❌ Bu janrda hali kino yo'q!"
                    : "❌ Hali kino qo'shilmagan!");
            return;
        }

        StringBuilder text = new StringBuilder("🎬 *Kinolar ro'yxati");
        if (genre != null) text.append(" — ").append(genreEmoji(genre)).append(" ").append(genre.name());
        text.append(":*\n\n");

        List<Movie> movies = moviePage.getContent();
        for (Movie m : movies) {
            text.append("🎥 `").append(m.getId()).append("` — ").append(m.getName()).append("\n");
        }
        text.append("\n📄 Sahifa ").append(page + 1).append(" / ").append(moviePage.getTotalPages());

        String pagePrefix = genre != null ? "page_" + genre.name() + "_" : "page_";

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (Movie m : movies) {
            row.add(btn(String.valueOf(m.getId()), "movie_" + m.getId()));
            if (row.size() == 5) { rows.add(new ArrayList<>(row)); row.clear(); }
        }
        if (!row.isEmpty()) rows.add(row);

        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0)                  nav.add(btn("◀️ Oldingi", pagePrefix + (page - 1)));
        if (moviePage.hasNext())       nav.add(btn("Keyingi ▶️", pagePrefix + (page + 1)));
        if (!nav.isEmpty())            rows.add(nav);

        rows.add(List.of(btn("🔙 Orqaga", genre != null ? "genres" : "menu")));

        executeSafe(SendMessage.builder()
                .chatId(chatId)
                .text(text.toString())
                .parseMode("Markdown")
                .protectContent(isContentProtected)
                .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                .build());
    }

    private Page<Movie> fetchPage(int page, Genre genre) {
        PageRequest pr = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"));
        return genre != null
                ? movieRepository.findByGenre(genre, pr)
                : movieRepository.findAll(pr);
    }


    private void sendVideo(Long chatId, Movie movie) {
        SendVideo sendVideo = new SendVideo();
        sendVideo.setChatId(chatId);
        sendVideo.setVideo(new InputFile(movie.getFileId()));
        sendVideo.setCaption(buildCaption(movie));
        sendVideo.setParseMode("Markdown");
        sendVideo.setProtectContent(isContentProtected);

        try {
            execute(sendVideo);
        } catch (TelegramApiException e) {
            log.error("sendVideo xatosi (chatId={}, movieId={}): {}", chatId, movie.getId(), e.getMessage());
            sendMessage(chatId, "⚠️ Kinoni yuborishda xato yuz berdi. Iltimos, keyinroq urinib ko'ring.");
        }
    }

    private String buildCaption(Movie movie) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎬 *").append(escapeMarkdown(movie.getName())).append("*\n\n");

        if (movie.getCode() != null && !movie.getCode().isBlank()) {
            sb.append("🔑 Kod: `").append(movie.getCode()).append("`\n");
        }
        if (movie.getGenres() != null && !movie.getGenres().isEmpty()) {
            String genres = movie.getGenres().stream()
                    .map(g -> genreEmoji(g) + " " + g.name())
                    .collect(Collectors.joining(", "));
            sb.append("🎭 Janr: ").append(genres).append("\n");
        }
        return sb.toString();
    }


    private void forwardReklamaToAllUsers(Long fromChatId, Integer messageId) {
        List<User> users = userRepository.findAll();
        int success = 0, fail = 0;

        for (User user : users) {
            if (user.getChat_id() == null) continue;
            try {
                execute(ForwardMessage.builder()
                        .chatId(user.getChat_id())
                        .fromChatId(fromChatId)
                        .messageId(messageId)
                        .build());
                success++;
            } catch (TelegramApiException e) {
                fail++;
                log.warn("Reklama yetkazilmadi: {} — {}", user.getChat_id(), e.getMessage());
            }
        }
        log.info("Reklama: {} ta yetkazildi, {} ta yetkazilmadi", success, fail);
    }


    private void executeSafe(SendMessage msg) {
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            log.error("executeSafe xatosi (chatId={}): {}", msg.getChatId(), e.getMessage());
        }
    }

    private void sendMessage(Long chatId, String text) {
        executeSafe(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown")
                .protectContent(isContentProtected)
                .build());
    }

    private InlineKeyboardButton btn(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("`", "\\`")
                .replace("[", "\\[");
    }

    private String genreEmoji(Genre genre) {
        return switch (genre) {
            case ACTION      -> "💥";
            case DRAMA       -> "🎭";
            case COMEDY      -> "😂";
            case HORROR      -> "👻";
            case ROMANCE     -> "❤️";
            case THRILLER    -> "😱";
            case ANIMATION   -> "🎨";
            case DOCUMENTARY -> "📽️";
            default          -> "🎬";
        };
    }
}