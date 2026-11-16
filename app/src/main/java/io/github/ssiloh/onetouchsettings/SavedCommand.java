package io.github.ssiloh.onetouchsettings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

final class SavedCommand {
    final String id;
    String title;
    String command;
    String description;
    String category;

    SavedCommand(String id, String title, String command, String description, String category) {
        this.id = id;
        this.title = title;
        this.command = command;
        this.description = description;
        this.category = category;
    }

    static SavedCommand create(String title, String command, String description, String category) {
        return new SavedCommand(UUID.randomUUID().toString(), title, command, description, category);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("title", title)
                .put("command", command)
                .put("description", description)
                .put("category", category);
    }

    static SavedCommand fromJson(JSONObject o) throws JSONException {
        String command = o.getString("command");
        return new SavedCommand(o.optString("id", UUID.randomUUID().toString()),
                o.optString("title", command), command, o.optString("description", ""), o.optString("category", ""));
    }
}
