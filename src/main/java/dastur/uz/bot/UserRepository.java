package dastur.uz.bot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {


    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.chat_id = :chatId")
    boolean existsByChatId(@Param("chatId") Long chatId);}
