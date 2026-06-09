package com.overdrive.app.server;

import com.overdrive.app.automation.Automation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.URLDecoder;

/**
 * REST surface for the JS automation engine, used by the web editor (automation.html).
 *
 *   GET  /api/automation/state                 -> { enabled, dryRun, triggers[], scenarios[] }
 *   POST /api/automation/state  { enabled?, dryRun? }
 *   GET  /api/automation/scenarios             -> { scenarios[] }
 *   GET  /api/automation/scenario?name=foo.js  -> { name, source }
 *   POST /api/automation/scenario { name, source }   (save + reload)
 *   DELETE /api/automation/scenario?name=foo.js      (delete + reload)
 *   POST /api/automation/fire   { type }       -> { ran, type }   (manual test fire)
 *   GET  /api/automation/audit                 -> { lines[] }
 */
public class AutomationApiHandler {

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String route = path;
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) { route = path.substring(0, q); query = path.substring(q + 1); }

        if (route.equals("/api/automation/state")) {
            if (method.equalsIgnoreCase("POST")) {
                JSONObject in;
                try { in = new JSONObject((body == null || body.trim().isEmpty()) ? "{}" : body); }
                catch (Exception e) { HttpResponse.sendError(out, 400, "invalid JSON body"); return true; }
                if (in.has("enabled")) Automation.INSTANCE.setEnabled(in.optBoolean("enabled", Automation.INSTANCE.isEnabled()));
                if (in.has("dryRun")) Automation.INSTANCE.setDryRun(in.optBoolean("dryRun", Automation.INSTANCE.isDryRun()));
            }
            HttpResponse.sendJson(out, stateJson().toString());
            return true;
        }

        if (route.equals("/api/automation/scenarios")) {
            JSONObject o = new JSONObject();
            o.put("scenarios", new JSONArray(Automation.INSTANCE.listScenarios()));
            HttpResponse.sendJson(out, o.toString());
            return true;
        }

        if (route.equals("/api/automation/scenario")) {
            if (method.equalsIgnoreCase("GET")) {
                String name = queryParam(query, "name");
                String src = (name == null) ? null : Automation.INSTANCE.readScenario(name);
                if (src == null) { HttpResponse.sendError(out, 404, "scenario not found"); return true; }
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("source", src);
                HttpResponse.sendJson(out, o.toString());
                return true;
            }
            if (method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT")) {
                JSONObject in;
                try { in = new JSONObject(body == null ? "" : body); }
                catch (Exception e) { HttpResponse.sendError(out, 400, "invalid JSON body"); return true; }
                String name = in.optString("name", "");
                String source = in.optString("source", "");
                if (name.isEmpty()) { HttpResponse.sendError(out, 400, "name required"); return true; }
                if (!Automation.INSTANCE.saveScenario(name, source)) {
                    HttpResponse.sendError(out, 400, "save failed: name must be a .js basename and the script must load without error");
                    return true;
                }
                HttpResponse.sendJson(out, stateJson().toString());
                return true;
            }
            if (method.equalsIgnoreCase("DELETE")) {
                String name = queryParam(query, "name");
                if (name == null || !Automation.INSTANCE.deleteScenario(name)) {
                    HttpResponse.sendError(out, 400, "invalid scenario name");
                    return true;
                }
                HttpResponse.sendJson(out, stateJson().toString());
                return true;
            }
        }

        if (route.equals("/api/automation/fire") && method.equalsIgnoreCase("POST")) {
            JSONObject in;
            try { in = new JSONObject(body == null ? "" : body); }
            catch (Exception e) { HttpResponse.sendError(out, 400, "invalid JSON body"); return true; }
            String type = in.optString("type", "");
            if (type.isEmpty()) { HttpResponse.sendError(out, 400, "type required"); return true; }
            int ran = Automation.INSTANCE.fireManual(type);
            JSONObject o = new JSONObject();
            o.put("ran", ran);
            o.put("type", type);
            HttpResponse.sendJson(out, o.toString());
            return true;
        }

        if (route.equals("/api/automation/audit") && method.equalsIgnoreCase("GET")) {
            JSONObject o = new JSONObject();
            o.put("lines", new JSONArray(Automation.INSTANCE.readAudit(200)));
            HttpResponse.sendJson(out, o.toString());
            return true;
        }

        HttpResponse.sendError(out, 404, "Unknown automation route: " + route);
        return true;
    }

    private static JSONObject stateJson() throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("enabled", Automation.INSTANCE.isEnabled());
        o.put("dryRun", Automation.INSTANCE.isDryRun());
        o.put("triggers", new JSONArray(Automation.INSTANCE.triggerTypesList()));
        o.put("scenarios", new JSONArray(Automation.INSTANCE.listScenarios()));
        return o;
    }

    private static String queryParam(String query, String key) {
        if (query == null) return null;
        for (String p : query.split("&")) {
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(key)) {
                try { return URLDecoder.decode(p.substring(eq + 1), "UTF-8"); }
                catch (Exception e) { return p.substring(eq + 1); }
            }
        }
        return null;
    }
}
