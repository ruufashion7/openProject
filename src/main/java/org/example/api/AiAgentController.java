package org.example.api;

import org.example.ai.AiAgentConversation;
import org.example.ai.AiAgentExport;
import org.example.ai.AiAgentMessage;
import org.example.ai.AiAgentService;
import org.example.auth.AuthSessionService;
import org.example.auth.SessionInfo;
import org.example.auth.SessionPermissions;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-agent")
public class AiAgentController {

    private final AuthSessionService authSessionService;
    private final AiAgentService aiAgentService;

    public AiAgentController(AuthSessionService authSessionService, AiAgentService aiAgentService) {
        this.authSessionService = authSessionService;
        this.aiAgentService = aiAgentService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessAiAgent(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(aiAgentService.status(session));
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<AiAgentConversation>> conversations(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessAiAgent(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(aiAgentService.listConversations(session.userId()));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<AiAgentMessage>> messages(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable("id") String id
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessAiAgent(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(aiAgentService.listMessages(id, session.userId()));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable("id") String id
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessAiAgent(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        aiAgentService.deleteConversation(id, session.userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody ChatRequest request
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessAiAgent(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            AiAgentService.ChatResult result = aiAgentService.chat(
                    session,
                    authHeader,
                    request == null ? null : request.conversationId(),
                    request == null ? null : request.message()
            );
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("conversationId", result.conversation().getId());
            body.put("title", result.conversation().getTitle());
            body.put("mode", result.mode());
            body.put("message", result.assistantMessage());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/downloads/{id}")
    public ResponseEntity<byte[]> download(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable("id") String id
    ) {
        SessionInfo session = requireSession(authHeader);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!SessionPermissions.canAccessAiAgent(session)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        AiAgentExport export = aiAgentService.getExport(id, session.userId());
        if (export == null || export.getContent() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.getFilename() + "\"")
                .contentType(MediaType.parseMediaType(
                        export.getContentType() == null ? "application/pdf" : export.getContentType()))
                .body(export.getContent());
    }

    private SessionInfo requireSession(String authHeader) {
        return authSessionService.validate(extractToken(authHeader));
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String t = authHeader.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        return t.isEmpty() ? null : t;
    }

    public record ChatRequest(String conversationId, String message) {
    }
}
