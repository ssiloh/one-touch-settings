package io.github.ssiloh.onetouchsettings;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists the user's command collection as JSON in app-private SharedPreferences. */
final class CommandStore {
    private static final String PREFS = "commands";
    private static final String KEY_LIST = "list";
    private static final String KEY_SEEDED = "seeded_v1";

    private final SharedPreferences sp;

    CommandStore(Context context) {
        sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(KEY_SEEDED, false)) {
            save(DefaultCommands.all());
            sp.edit().putBoolean(KEY_SEEDED, true).apply();
        }
    }

    List<SavedCommand> load() {
        List<SavedCommand> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(sp.getString(KEY_LIST, "[]"));
            for (int i = 0; i < array.length(); i++) list.add(SavedCommand.fromJson(array.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    void save(List<SavedCommand> list) {
        JSONArray array = new JSONArray();
        try {
            for (SavedCommand c : list) array.put(c.toJson());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        sp.edit().putString(KEY_LIST, array.toString()).apply();
    }

    void upsert(SavedCommand command) {
        List<SavedCommand> list = load();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(command.id)) {
                list.set(i, command);
                replaced = true;
            }
        }
        if (!replaced) list.add(command);
        save(list);
    }

    void delete(String id) {
        List<SavedCommand> list = load();
        list.removeIf(c -> c.id.equals(id));
        save(list);
    }

    /** Adds back any bundled command that is missing. Returns how many were added. */
    int restoreDefaults() {
        List<SavedCommand> list = load();
        int added = 0;
        for (SavedCommand d : DefaultCommands.all()) {
            boolean present = false;
            for (SavedCommand c : list) if (c.id.equals(d.id)) present = true;
            if (!present) {
                list.add(d);
                added++;
            }
        }
        save(list);
        return added;
    }

    String exportJson() {
        JSONArray array = new JSONArray();
        try {
            for (SavedCommand c : load()) array.put(c.toJson());
            return new JSONObject().put("app", "one-touch-settings").put("version", 1).put("commands", array)
                    .toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Merges commands from exported JSON (by id). Returns how many were imported. */
    int importJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray array = root.getJSONArray("commands");
        int count = 0;
        for (int i = 0; i < array.length(); i++) {
            upsert(SavedCommand.fromJson(array.getJSONObject(i)));
            count++;
        }
        return count;
    }
}
