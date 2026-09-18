package dev.worldcup.api;

import dev.worldcup.identity.ActorService;
import dev.worldcup.shared.Failure;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AnonymousActor {
    private final ActorService actors;
    private final boolean secure;
    public AnonymousActor(ActorService actors, @Value("${worldcup.cookie-secure}") boolean secure) {
        this.actors = actors; this.secure = secure;
    }
    public String resolve(HttpServletRequest request, HttpServletResponse response, boolean allowCreate) {
        Cookie[] cookies = request.getCookies();
        String token = cookies == null ? null : Arrays.stream(cookies).filter(c -> "worldcup_actor".equals(c.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
        var actor = actors.find(token);
        if (actor.isPresent()) return actor.get();
        if (!allowCreate) throw Failure.of(Failure.Code.NOT_FOUND);
        var issued = actors.issue();
        response.addHeader("Set-Cookie", ResponseCookie.from("worldcup_actor", issued.token())
                .httpOnly(true).secure(secure).sameSite("Lax").path("/api/v1").maxAge(Duration.ofDays(30)).build().toString());
        return issued.actorId();
    }
}
