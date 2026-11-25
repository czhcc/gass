package com.trs.simulation.api;

import com.trs.simulation.SimulationTypes.Outcome;
import com.trs.simulation.SimulationTypes.World;
import com.trs.simulation.action.library.ActionLibraryService;
import com.trs.simulation.action.library.ActionLibraryService.Entry;
import com.trs.simulation.action.library.ActionLibraryService.Generation;
import com.trs.simulation.action.library.ActionLibraryService.Input;
import com.trs.simulation.action.library.ActionLibraryService.Preview;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated authoring, generation and explicit world binding endpoints. @author czhcc-trsdsj\czhcc */
@RestController
@RequestMapping("/api/action-library")
public class ActionLibraryController {
    private final ActionLibraryService library;
    /** Injects the versioned library. @author czhcc-trsdsj\czhcc */
    public ActionLibraryController(ActionLibraryService library) {
        this.library = library;
    }
    /** Lists all revisions. @author czhcc-trsdsj\czhcc */
    @GetMapping
    public List<Entry> list() {
        return library.list();
    }
    /** Creates a draft. @author czhcc-trsdsj\czhcc */
    @PostMapping
    public Entry create(@RequestBody Input input) {
        return library.create(input);
    }
    /** Edits an unpublished revision. @author czhcc-trsdsj\czhcc */
    @PutMapping("/{id}")
    public Entry update(@PathVariable String id, @RequestBody Input input) {
        return library.update(id, input);
    }
    /** Compiles without executing effects. @author czhcc-trsdsj\czhcc */
    @PostMapping("/validate")
    public Map<String, Boolean> validate(@RequestBody Input input) {
        library.validate(input);
        return Map.of("valid", true);
    }
    /** Generates a draft with the selected saved model. @author czhcc-trsdsj\czhcc */
    @PostMapping("/generate")
    public Entry generate(@RequestBody Generation input) {
        return library.generate(input);
    }
    /** Simulates effects without persisting the resulting graph. @author czhcc-trsdsj\czhcc */
    @PostMapping("/preview")
    public Outcome preview(@RequestBody Preview input) {
        return library.preview(input);
    }
    /** Version check for draft mutations. @author czhcc-trsdsj\czhcc */
    public record Version(int version) {}
    /** Publishes an immutable revision. @author czhcc-trsdsj\czhcc */
    @PostMapping("/{id}/publish")
    public Entry publish(@PathVariable String id, @RequestBody Version input) {
        return library.publish(id, input.version());
    }
    /** Removes a draft only. @author czhcc-trsdsj\czhcc */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam int version) {
        library.delete(id, version);
    }
    /** Expected world version prevents overwriting another editor's bindings. @author czhcc-trsdsj\czhcc */
    public record Binding(String worldId, int expectedVersion) {}
    /** Binds the selected published revision to a future world template. @author czhcc-trsdsj\czhcc */
    @PostMapping("/{id}/bind")
    public World bind(@PathVariable String id, @RequestBody Binding input) {
        return library.bind(id, input.worldId(), input.expectedVersion());
    }
    /** Identifies an object-type binding to remove. @author czhcc-trsdsj\czhcc */
    public record Unbinding(String worldId, String ownerType, String actionType, int expectedVersion) {}
    /** Removes a binding from the future template only. @author czhcc-trsdsj\czhcc */
    @PostMapping("/unbind")
    public World unbind(@RequestBody Unbinding input) {
        return library.unbind(input.worldId(), input.ownerType(), input.actionType(), input.expectedVersion());
    }
}
