import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import es.uc3m.miaa.utils.Entity;
import es.uc3m.miaa.utils.MyGATE;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Annotator {

    private static final String DEFAULT_CLASS = "urn:uc3m.es:miaa#webPage";
    private static final Pattern WIKIPEDIA_URL =
        Pattern.compile("https://en\\.wikipedia\\.org/wiki/[A-Za-z0-9_()%.,\\-]+");

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 3) {
            printUsage();
            return;
        }

        String inputFile = args[0];
        String pageClass = DEFAULT_CLASS;

        if (args.length == 3) {
            if (!"-C".equals(args[1])) {
                printUsage();
                return;
            }
            pageClass = args[2];
        }

        String apiKey = System.getenv("GEMINI_API_KEY");
        String openRouterApiKey = System.getenv("OPENROUTER_API_KEY");
        Client client = null;
        if (apiKey != null && apiKey.trim().length() > 0) {
            client = Client.builder().apiKey(apiKey.trim()).build();
        }

        List<String> urls = readUrls(inputFile);
        String outputFile = buildOutputFile(inputFile);
        PrintWriter writer = new PrintWriter(new FileWriter(outputFile));

        try {
            writeHeader(writer);

            MyGATE gate = MyGATE.getInstance();

            for (int i = 0; i < urls.size(); i++) {
                String urlText = urls.get(i);
                URL url = new URL(urlText);
                List<Entity> entities = gate.findEntities(url);
                if (entities == null) {
                    System.err.println("Skipping " + urlText + " because no entities could be extracted.");
                    continue;
                }
                entities = removeDuplicateEntities(entities);

                writer.println("<" + urlText + "> a <" + pageClass + "> .");

                for (int j = 0; j < entities.size(); j++) {
                    Entity entity = entities.get(j);
                    String entityUri = "urn:uc3m.es:miaa#entity" + i + "_" + j;

                    writer.println("<" + entityUri + "> a miaa:" + entity.getType() + " .");
                    writer.println("<" + entityUri + "> miaa:entityName " + literal(entity.getText()) + " .");
                    writer.println("<" + urlText + "> miaa:mentionsEntity <" + entityUri + "> .");
                }

                if (client != null || hasText(openRouterApiKey)) {
                    List<String> wikiUrls = askForLocations(client, openRouterApiKey, entities);
                    for (int j = 0; j < wikiUrls.size(); j++) {
                        String wikiUrl = wikiUrls.get(j);
                        writer.println("<" + urlText + "> miaa:mentionsInstance <" + wikiUrl + "> .");
                        writer.println("<" + wikiUrl + "> a dcterms:Location .");
                    }
                }

                writer.println();
            }
        } finally {
            writer.close();
        }

        System.out.println("Turtle generated in: " + outputFile);
    }

    private static List<String> readUrls(String inputFile) throws Exception {
        List<String> urls = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new FileReader(inputFile));

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!"".equals(line)) {
                    urls.add(line);
                }
            }
        } finally {
            reader.close();
        }

        return urls;
    }

    private static void writeHeader(PrintWriter writer) {
        writer.println("@base <urn:uc3m.es:miaa> .");
        writer.println("@prefix miaa: <urn:uc3m.es:miaa#> .");
        writer.println("@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .");
        writer.println("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .");
        writer.println("@prefix dcterms: <http://purl.org/dc/terms/> .");
        writer.println();
    }

    private static List<String> askForLocations(Client client, String openRouterApiKey, List<Entity> entities) {
        Set<String> urls = new LinkedHashSet<String>();
        String prompt = buildLocationsPrompt(entities);

        if (client != null) {
            urls.addAll(askGeminiForLocations(client, prompt));
        }

        if (urls.isEmpty() && hasText(openRouterApiKey)) {
            urls.addAll(askOpenRouterForLocations(openRouterApiKey, prompt));
        }

        return new ArrayList<String>(urls);
    }

    private static List<String> askGeminiForLocations(Client client, String prompt) {
        Set<String> urls = new LinkedHashSet<String>();

        try {
            String instruction =
                "I will give you the named entities detected in one document. " +
                "Return ONLY the English Wikipedia URLs for the entities of type Location, one per line. " +
                "Do not include explanations. If none can be identified, return ONLY NONE.";

            GenerateContentConfig config =
                GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(instruction)))
                    .build();

            System.out.println("Calling Gemini with document entities...");
            GenerateContentResponse response =
                client.models.generateContent("gemini-2.5-flash", prompt, config);
            System.out.println("Gemini raw response:");
            System.out.println(response.text());

            Matcher matcher = WIKIPEDIA_URL.matcher(response.text());
            while (matcher.find()) {
                urls.add(matcher.group());
            }
        } catch (Exception e) {
            System.err.println("Gemini error resolving locations");
        }

        return new ArrayList<String>(urls);
    }

    private static List<String> askOpenRouterForLocations(String openRouterApiKey, String prompt) {
        Set<String> urls = new LinkedHashSet<String>();

        try {
            URL endpoint = new URL("https://openrouter.ai/api/v1/chat/completions");
            HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + openRouterApiKey.trim());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("model", "google/gemini-2.5-flash-lite");

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                .put("role", "system")
                .put("content",
                    "Return ONLY the English Wikipedia URLs for the entities of type Location, one per line. " +
                    "Do not include explanations. If none can be identified, return ONLY NONE."));
            messages.put(new JSONObject().put("role", "user").put("content", prompt));
            body.put("messages", messages);

            OutputStream os = connection.getOutputStream();
            try {
                os.write(body.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder responseBuilder = new StringBuilder();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
            } finally {
                reader.close();
            }

            JSONObject responseJson = new JSONObject(responseBuilder.toString());
            String content = responseJson
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

            System.out.println("OpenRouter raw response:");
            System.out.println(content);

            Matcher matcher = WIKIPEDIA_URL.matcher(content);
            while (matcher.find()) {
                urls.add(matcher.group());
            }
        } catch (Exception e) {
            System.err.println("OpenRouter error resolving locations");
        }

        return new ArrayList<String>(urls);
    }

    private static String buildLocationsPrompt(List<Entity> entities) {
        String prompt = "Entities in the document:\n";
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            prompt += entity.getType() + ": " + entity.getText() + "\n";
        }
        return prompt;
    }

    private static List<Entity> removeDuplicateEntities(List<Entity> entities) {
        List<Entity> uniqueEntities = new ArrayList<Entity>();
        Set<String> seen = new LinkedHashSet<String>();

        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            String key = entity.getType() + "::" + entity.getText();
            if (!seen.contains(key)) {
                seen.add(key);
                uniqueEntities.add(entity);
            }
        }

        return uniqueEntities;
    }

    private static String literal(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean hasText(String text) {
        return text != null && text.trim().length() > 0;
    }

    private static String buildOutputFile(String inputFile) {
        File file = new File(inputFile);
        String name = file.getName();
        int pos = name.lastIndexOf('.');
        String outName;

        if (pos >= 0) {
            outName = name.substring(0, pos) + ".ttl";
        } else {
            outName = name + ".ttl";
        }

        if (file.getParentFile() == null) {
            return outName;
        }

        return new File(file.getParentFile(), outName).getPath();
    }

    private static void printUsage() {
        System.err.println("Usage: java Annotator <fichero> [-C <clase>]");
    }
}
