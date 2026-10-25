package com.opencodebuddy.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class McpServerHandlerTest {

    @Test
    public void normalizesOpenCodeServerListShape() {
        JsonObject response = new JsonObject();
        JsonArray servers = new JsonArray();
        JsonObject winui = new JsonObject();
        winui.addProperty("name", "winui");
        servers.add(winui);
        response.add("servers", servers);

        List<JsonObject> parsed = McpServerHandler.parseOpenCodeMcpServers(response);

        assertEquals(1, parsed.size());
        assertEquals("winui", parsed.get(0).get("id").getAsString());
        assertEquals("winui", parsed.get(0).get("name").getAsString());
        assertTrue(parsed.get(0).get("enabled").getAsBoolean());
        assertEquals("stdio", parsed.get(0).getAsJsonObject("server").get("type").getAsString());
    }

    @Test
    public void acceptsAnArrayAndSkipsNonObjects() {
        JsonArray response = new JsonArray();
        response.add("ignore-me");
        JsonObject winui = new JsonObject();
        winui.addProperty("id", "winui-dev");
        response.add(winui);

        List<JsonObject> parsed = McpServerHandler.parseOpenCodeMcpServers(response);

        assertEquals(1, parsed.size());
        assertEquals("winui-dev", parsed.get(0).get("name").getAsString());
    }

    @Test
    public void returnsAnEmptyListForAnEmptyResponse() {
        JsonObject response = new JsonObject();
        response.add("servers", new JsonArray());

        assertTrue(McpServerHandler.parseOpenCodeMcpServers(response).isEmpty());
        assertTrue(McpServerHandler.parseOpenCodeMcpServers(null).isEmpty());
    }
}
