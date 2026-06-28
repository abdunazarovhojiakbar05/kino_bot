package dastur.uz.bot;

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

@Component
public class Bot extends TelegramLongPollingBot {

    private final MovieRepository movieRepository;
    private final UserRepository userRepository;
    private static final int PAGE_SIZE = 10;

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
    public String getBotUsername() { return this.botUsername; }

    @Override
    public String getBotToken() { return this.botToken; }

    @Override
    public void onUpdateReceived(Update update) {

        if (update.hasChannelPost()) {
            handleChannelPost(update.getChannelPost());
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            handleMessage(update.getMessage());
            return;
        }

        if (update.hasCallbackQuery()) {
            handleCallback(
                    update.getCallbackQuery().getData(),
                    update.getCallbackQuery().getMessage().getChatId()
            );
        }
    }


    private void handleChannelPost(Message post) {
        String caption = "";
        if (post.hasVideo() && post.getCaption() != null) {
            caption = post.getCaption().trim();
        } else if (post.hasText()) {
            caption = post.getText().trim();
        }

        if (!caption.isEmpty() &&
                (caption.toLowerCase().contains("reklama") ||
                        caption.toLowerCase().contains("#reklama"))) {
            forwardReklamaToAllUsers(post.getChatId(), post.getMessageId());
            return;
        }

        if (post.hasVideo() && post.getCaption() != null) {
            String[] parts = post.getCaption().trim().split("\\|");

            Movie movie = new Movie();
            movie.setName(parts[0].trim().toLowerCase());
            movie.setFileId(post.getVideo().getFileId());

            if (parts.length > 1) {
                movie.setCode(parts[1].trim());
            }
            if (parts.length > 2) {
                Set<Genre> genres = new HashSet<>();
                for (String g : parts[2].trim().split(",")) {
                    try {
                        genres.add(Genre.valueOf(g.trim().toUpperCase()));
                    } catch (IllegalArgumentException ignored) {}
                }
                movie.setGenres(genres);
            }

            movieRepository.save(movie);
        }
    }

    private void handleMessage(Message message) {
        String text = message.getText().trim();
        Long chatId = message.getChatId();

        // /start
        if (text.equals("/start")) {
            if (!userRepository.existsByChatId(chatId)) {
                User user = new User();
                user.setChat_id(chatId);
                userRepository.save(user);
            }
            sendStartMenu(chatId);
            return;
        }


        if (text.equals("/list")) {
            sendMovieList(chatId, 0, null);
            return;
        }

        Optional<Movie> byCode = movieRepository.findByCodeIgnoreCase(text);
        if (byCode.isPresent()) {
            sendVideo(chatId, byCode.get());
            return;
        }

        Optional<Movie> byName = movieRepository.findByNameIgnoreCase(text);
        if (byName.isPresent()) {
            sendVideo(chatId, byName.get());
            return;
        }

        sendMessage(chatId, "❌ Kino topilmadi: " + text);
    }


    private void handleCallback(String data, Long chatId) {
        if (data == null) return;

        if (data.equals("menu")) {
            sendStartMenu(chatId);
            return;
        }

        if (data.equals("genres")) {
            sendGenreMenu(chatId);
            return;
        }

        if (data.equals("all_list")) {
            sendMovieList(chatId, 0, null);
            return;
        }

        if (data.startsWith("movie_")) {
            try {
                Long movieId = Long.parseLong(data.replace("movie_", ""));
                movieRepository.findById(movieId).ifPresentOrElse(
                        movie -> sendVideo(chatId, movie),
                        () -> sendMessage(chatId, "❌ Kino topilmadi!")
                );
            } catch (NumberFormatException e) {
                sendMessage(chatId, "❌ Noto'g'ri so'rov!");
            }
            return;
        }

        if (data.startsWith("genre_") && data.split("_").length == 2) {
            try {
                Genre genre = Genre.valueOf(data.split("_")[1]);
                sendMovieList(chatId, 0, genre);
            } catch (IllegalArgumentException e) {
                sendMessage(chatId, "❌ Noto'g'ri janr!");
            }
            return;
        }

        if (data.startsWith("page_") && data.split("_").length == 3) {
            String[] parts = data.split("_");
            try {
                Genre genre = Genre.valueOf(parts[1]);
                int page = Integer.parseInt(parts[2]);
                sendMovieList(chatId, page, genre);
            } catch (IllegalArgumentException e) {
                sendMessage(chatId, "❌ Noto'g'ri so'rov!");
            }
            return;
        }

        if (data.startsWith("page_") && data.split("_").length == 2) {
            try {
                int page = Integer.parseInt(data.split("_")[1]);
                sendMovieList(chatId, page, null);
            } catch (NumberFormatException e) {
                sendMessage(chatId, "❌ Noto'g'ri sahifa!");
            }
        }
    }


    private void sendStartMenu(Long chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(
                InlineKeyboardButton.builder()
                        .text("🎬 Barcha kinolar")
                        .callbackData("all_list")
                        .build()
        ));

        rows.add(List.of(
                InlineKeyboardButton.builder()
                        .text("🎭 Janrlar bo'yicha")
                        .callbackData("genres")
                        .build()
        ));

        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("🎬 *Kino botga xush kelibsiz!*\n\n" +
                            "Kino nomini yoki kodini kiriting.\n" +
                            "Yoki quyidagi tugmalardan foydalaning:")
                    .parseMode("Markdown")
                    .protectContent(isContentProtected)
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }


    private void sendGenreMenu(Long chatId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        for (Genre genre : Genre.values()) {
            row.add(InlineKeyboardButton.builder()
                    .text(genreEmoji(genre) + " " + genre.name())
                    .callbackData("genre_" + genre.name())
                    .build());
            if (row.size() == 2) {
                rows.add(new ArrayList<>(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) rows.add(row);

        rows.add(List.of(
                InlineKeyboardButton.builder()
                        .text("🔙 Orqaga")
                        .callbackData("menu")
                        .build()
        ));

        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("🎭 *Janrni tanlang:*")
                    .parseMode("Markdown")
                    .protectContent(isContentProtected)
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }


    private void sendMovieList(Long chatId, int page, Genre genre) {
        Page<Movie> moviePage;
        String prefix;

        if (genre != null) {
            moviePage = movieRepository.findByGenre(genre,
                    PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")));
            prefix = "page_" + genre.name() + "_";
        } else {
            moviePage = movieRepository.findAll(
                    PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")));
            prefix = "page_";
        }

        List<Movie> movies = moviePage.getContent();
        if (movies.isEmpty()) {
            sendMessage(chatId, "❌ Hali kino qo'shilmagan!");
            return;
        }

        StringBuilder text = new StringBuilder("🎬 *Kinolar ro'yxati");
        if (genre != null) text.append(" — ").append(genreEmoji(genre)).append(" ").append(genre.name());
        text.append(":*\n\n");

        for (Movie movie : movies) {
            text.append("🎥 `").append(movie.getId()).append("` — ")
                    .append(movie.getName()).append("\n");
        }
        text.append("\n📄 Sahifa ").append(page + 1).append(" / ")
                .append(moviePage.getTotalPages());

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        for (Movie movie : movies) {
            row.add(InlineKeyboardButton.builder()
                    .text(String.valueOf(movie.getId()))
                    .callbackData("movie_" + movie.getId())
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
                    .text("◀️ Oldingi")
                    .callbackData(prefix + (page - 1))
                    .build());
        }
        if (moviePage.hasNext()) {
            navRow.add(InlineKeyboardButton.builder()
                    .text("Keyingi ▶️")
                    .callbackData(prefix + (page + 1))
                    .build());
        }
        if (!navRow.isEmpty()) rows.add(navRow);

        rows.add(List.of(
                InlineKeyboardButton.builder()
                        .text("🔙 Orqaga")
                        .callbackData(genre != null ? "genres" : "menu")
                        .build()
        ));

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
        sendVideo.setCaption(buildCaption(movie));
        sendVideo.setParseMode("Markdown");
        sendVideo.setProtectContent(isContentProtected);
        try {
            execute(sendVideo);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }


    private String buildCaption(Movie movie) {
        StringBuilder caption = new StringBuilder();
        caption.append("🎬 *").append(movie.getName()).append("*\n\n");

        if (movie.getCode() != null) {
            caption.append("🔑 Kod: `").append(movie.getCode()).append("`\n");
        }
        if (movie.getGenres() != null && !movie.getGenres().isEmpty()) {
            String genres = movie.getGenres().stream()
                    .map(g -> genreEmoji(g) + " " + g.name())
                    .collect(Collectors.joining(", "));
            caption.append("🎭 Janr: ").append(genres).append("\n");
        }

        return caption.toString();
    }


    private void forwardReklamaToAllUsers(Long fromChatId, Integer messageId) {
        List<User> users = userRepository.findAll();
        for (User user : users) {
            if (user.getChat_id() == null) continue;
            try {
                execute(ForwardMessage.builder()
                        .chatId(user.getChat_id())
                        .fromChatId(fromChatId)
                        .messageId(messageId)
                        .build());
            } catch (TelegramApiException e) {
                System.out.println("Reklama yetkazilmadi: " + user.getChat_id());
            }
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

    private String genreEmoji(Genre genre) {
        return switch (genre) {
            case ACTION   -> "💥";
            case DRAMA    -> "🎭";
            case COMEDY   -> "😂";
            case HORROR   -> "👻";
            case ROMANCE  -> "❤️";
            case THRILLER -> "😱";
            case ANIMATION  -> "🎨";
            case DOCUMENTARY -> "📽️";
            default       -> "🎬";
        };
    }
}