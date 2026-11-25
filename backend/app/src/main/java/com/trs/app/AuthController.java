package com.trs.app;

import com.trs.common.ApiUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same-origin session authentication endpoints. @author agent */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {
    private final UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** Injects account persistence. @author agent */
    public AuthController(UserRepository users) {
        this.users = users;
    }

    /** Login payload. @author agent */
    public record Login(String username, String password) {}

    /** Verifies credentials and rotates the session identifier. @author agent */
    @PostMapping("/login")
    public ResponseEntity<ApiUser> login(@RequestBody Login body, HttpServletRequest request) {
        if (body.username() == null
                || body.password() == null
                || body.password().length() > 72)
            return ResponseEntity.status(401).build();
        var user = users.findByUsername(body.username());
        if (user.isEmpty() || !encoder.matches(body.password(), user.get().getPasswordHash()))
            return ResponseEntity.status(401).build();
        var session = request.getSession();
        request.changeSessionId();
        session.setAttribute("username", user.get().getUsername());
        log.info("User signed in");
        return ResponseEntity.ok(new ApiUser(user.get().getUsername()));
    }

    /** Returns the current authenticated account. @author agent */
    @GetMapping("/me")
    public ResponseEntity<ApiUser> me(HttpServletRequest request) {
        var session = request.getSession(false);
        var name = session == null ? null : (String) session.getAttribute("username");
        return name == null ? ResponseEntity.status(401).build() : ResponseEntity.ok(new ApiUser(name));
    }

    /** Invalidates the current session. @author agent */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }
}
