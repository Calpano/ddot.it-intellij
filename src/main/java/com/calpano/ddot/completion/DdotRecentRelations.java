package com.calpano.ddot.completion;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-project most-recently-used list of ddot relations, used to boost recently
 * inserted predicates to the top of completion. Persisted in the workspace file
 * (not shared via VCS — it is a per-developer signal).
 */
@Service(Service.Level.PROJECT)
@State(name = "DdotRecentRelations", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class DdotRecentRelations implements PersistentStateComponent<DdotRecentRelations.State> {

    /** Keep a few more than we surface, so churn doesn't immediately evict useful entries. */
    private static final int MAX = 20;

    /** Serialized state: relations, most-recent first. */
    public static final class State {
        public List<String> recent = new ArrayList<>();
    }

    private State state = new State();

    public static DdotRecentRelations getInstance(@NotNull Project project) {
        return project.getService(DdotRecentRelations.class);
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    /** Record a relation as just used: move it to the front, capped at {@link #MAX}. */
    public synchronized void recordUse(@NotNull String relation) {
        if (relation.isEmpty()) return;
        state.recent.remove(relation);
        state.recent.add(0, relation);
        while (state.recent.size() > MAX) state.recent.remove(state.recent.size() - 1);
    }

    /** The {@code n} most recently used relations, most-recent first. */
    public synchronized @NotNull List<String> recent(int n) {
        int count = Math.min(n, state.recent.size());
        return new ArrayList<>(state.recent.subList(0, count));
    }
}
