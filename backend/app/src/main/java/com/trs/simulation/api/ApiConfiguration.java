package com.trs.simulation.api;

import com.trs.simulation.runtime.RunService;
import com.trs.simulation.world.WorldService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Authenticated same-origin API and safe domain error mapping. @author agent */
@Configuration
public class ApiConfiguration implements WebMvcConfigurer {
    /** Creates explicit JDBC/JPA transaction boundaries. @author agent */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    /** Requires a login session for all business APIs. @author agent */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    /** Rejects unauthenticated business operations. @author agent */
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                            throws Exception {
                        var session = request.getSession(false);
                        if (session == null || session.getAttribute("username") == null) {
                            response.sendError(401);
                            return false;
                        }
                        return true;
                    }
                })
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }

    /** Maps domain errors without leaking database/provider internals. @author agent */
    @RestControllerAdvice
    public static class Errors {
        /** Reports safe run deletion conflicts. @author czhcc-trsdsj\codexsandboxoffline */
        @ExceptionHandler(RunService.DeletionConflict.class)
        public ResponseEntity<Map<String, String>> runDeletionConflict(RunService.DeletionConflict e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage(), "code", e.code()));
        }

        /** Reports safe, actionable world maintenance conflicts. @author czhcc-trsdsj\codexsandboxoffline */
        @ExceptionHandler(WorldService.Conflict.class)
        public ResponseEntity<Map<String, String>> worldConflict(WorldService.Conflict e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage(), "code", e.code()));
        }

        /** Reports bad input. @author agent */
        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", e.getMessage() == null ? "Invalid request" : e.getMessage()));
        }

        /** Reports missing resources. @author agent */
        @ExceptionHandler(NoSuchElementException.class)
        public ResponseEntity<Map<String, String>> missing() {
            return ResponseEntity.status(404).body(Map.of("message", "Resource not found"));
        }

        /** Reports invalid lifecycle transitions. @author agent */
        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<Map<String, String>> conflict() {
            return ResponseEntity.status(409)
                    .body(Map.of("message", "Operation unavailable in current state or configuration"));
        }
    }
}
