package com.finalysis.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The chat API (docs/plans/phase-6.md §10.2). Conversations are the user's to create, read and delete; a turn asks one
 * question. Deleting is a user action here, never a tool: it removes the conversation's turns, tool calls and summary.
 * The monthly summary is {@code MonthlySummaryController}'s; the search index refresh is categorization's.
 */
@RestController
@RequestMapping("/api/chat/conversations")
public class ChatController {

    /** A question: 1 to 1,000 characters, as typed. */
    public record TurnRequest(@NotBlank @Size(max = 1000) String message) {
    }

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @PostMapping
    public ResponseEntity<ConversationResponses.Created> create() {
        ConversationResponses.Created created = chat.create();
        return ResponseEntity.created(URI.create("/api/chat/conversations/" + created.id())).body(created);
    }

    @GetMapping
    public ConversationResponses.Page list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return chat.list(page, size);
    }

    @GetMapping("/{id}")
    public ConversationResponses.Detail get(@PathVariable long id) {
        return chat.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        chat.delete(id);
    }

    @PostMapping("/{id}/turns")
    public ChatTurnResponse ask(@PathVariable long id, @Valid @RequestBody TurnRequest request) {
        return chat.ask(id, request.message());
    }
}
