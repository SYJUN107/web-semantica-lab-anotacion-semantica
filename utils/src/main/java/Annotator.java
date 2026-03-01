import es.uc3m.miaa.utils.Entity;
import es.uc3m.miaa.utils.MyGATE;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;

/**
 * Annotator – herramienta de anotación semántica.
 *
 * Uso:
 * java -cp ".;utils-1.0-jar-with-dependencies.jar" Annotator <fichero> [-C
 * <clase>] [-llm <gemini|ollama>]
 *
 * Lee una lista de URLs (una por línea) desde <fichero>, las procesa con
 * GATE/ANNIE para extraer entidades con nombre, consulta al LLM indicado la URL
 * de Wikipedia para las entidades de tipo Location, y escribe las anotaciones
 * en un fichero Turtle (.ttl) con el mismo nombre base que <fichero>.
 *
 * La API Key de Gemini se lee de la variable de entorno GEMINI_API_KEY.
 */
public class Annotator {

    // ── Namespaces ──────────────────────────────────────────────────────────
    private static final String MIAA = "urn:uc3m.es:miaa#";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";

    // ── Clase por defecto para páginas web ──────────────────────────────────
    private static final String DEFAULT_WEBPAGE_CLASS = MIAA + "webPage";

    // ── API Key de Gemini: se lee de la variable de entorno GEMINI_API_KEY ──
    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY") != null
            ? System.getenv("GEMINI_API_KEY")
            : "INTRODUCE_AQUI_TU_API_KEY";

    // ── Modelo de Gemini a utilizar ─────────────────────────────────────────
    private static final String GEMINI_MODEL = "gemini-2.5-flash";

    // ── Cliente Gemini (se inicializa la primera vez que se necesita) ───────
    private Client geminiClient = null;

    // ── Modelo de Ollama a utilizar ─────────────────────────────────────────
    private static final String OLLAMA_MODEL = System.getenv("OLLAMA_MODEL") != null
            ? System.getenv("OLLAMA_MODEL")
            : "gemma3:4b"; // Modelo por defecto detectado localmente

    // ── URL de la API de Ollama ─────────────────────────────────────────────
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";

    // ── Contador para generar URIs únicas para las entidades ────────────────
    private int entityCounter = 0;

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {

        if (args.length < 1) {
            System.err.println("Uso: java Annotator <fichero> [-C <clase>] [-llm <gemini|ollama>]");
            System.exit(1);
        }

        String inputFileName = args[0];
        String rdfClass = DEFAULT_WEBPAGE_CLASS;
        String llm = "ollama"; // por defecto

        // Procesar argumentos opcionales
        for (int i = 1; i < args.length; i++) {
            if ("-C".equals(args[i]) && i + 1 < args.length) {
                rdfClass = args[i + 1];
                i++;
            } else if ("-llm".equals(args[i]) && i + 1 < args.length) {
                llm = args[i + 1].toLowerCase();
                i++;
            }
        }

        new Annotator().run(inputFileName, rdfClass, llm);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LÓGICA PRINCIPAL
    // ═══════════════════════════════════════════════════════════════════════
    private void run(String inputFileName, String rdfClass, String llm) throws Exception {

        // Calcular nombre del fichero de salida (misma ruta, extensión .ttl)
        Path inputPath = Paths.get(inputFileName);
        String baseName = inputPath.getFileName().toString();
        int dot = baseName.lastIndexOf('.');
        String stem = (dot >= 0) ? baseName.substring(0, dot) : baseName;
        Path outputPath = inputPath.resolveSibling(stem + ".ttl");

        // Leer lista de URLs (ignorar líneas vacías y comentarios con #)
        Set<String> urls = new LinkedHashSet<String>();
        BufferedReader br = new BufferedReader(new FileReader(inputPath.toFile()));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                urls.add(line);
            }
        }
        br.close();

        // Inicializar GATE (singleton — carga ANNIE una sola vez)
        System.out.println("[INFO] Inicializando GATE/ANNIE...");
        MyGATE gate = MyGATE.getInstance();
        System.out.println("[INFO] GATE/ANNIE listo.");
        System.out.println("[INFO] Usando LLM: " + llm);

        // Abrir fichero Turtle de salida
        PrintWriter out = new PrintWriter(
                new BufferedWriter(new FileWriter(outputPath.toFile())));

        try {
            writePrefixes(out);

            for (String urlStr : urls) {
                System.out.println("[INFO] Procesando: " + urlStr);
                processUrl(urlStr, rdfClass, gate, out, llm);
            }
        } finally {
            out.close();
        }

        System.out.println("[INFO] Resultado escrito en: " + outputPath);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PROCESAMIENTO DE UNA URL
    // ═══════════════════════════════════════════════════════════════════════
    private void processUrl(String urlStr, String rdfClass,
            MyGATE gate, PrintWriter out, String llm) {
        try {
            URL url = new URL(urlStr);

            // ── 1. Declarar la página web como instancia de rdfClass ──────
            out.println("<" + urlStr + ">");
            out.println("    a <" + rdfClass + "> .");
            out.println();

            // ── 2. Extraer entidades con nombre usando ANNIE ──────────────
            List<Entity> entities = gate.findEntities(url);

            if (entities == null || entities.isEmpty()) {
                System.out.println("[WARN] No se encontraron entidades en: " + urlStr);
                return;
            }

            System.out.println("[INFO] Entidades encontradas: " + entities.size());

            // Recopilar nombres de todas las entidades del documento
            // (se usarán como contexto para la consulta al LLM)
            List<String> allEntityNames = new ArrayList<String>();
            for (Entity e : entities) {
                if (e.getText() != null && !e.getText().isEmpty()) {
                    allEntityNames.add(e.getType() + ": " + e.getText());
                }
            }

            // Eliminar entidades duplicadas para no repetir anotaciones
            Set<String> seen = new HashSet<String>();
            List<Entity> uniqueEntities = new ArrayList<Entity>();
            for (Entity e : entities) {
                String key = e.getType() + "::" + e.getText();
                if (seen.add(key)) {
                    uniqueEntities.add(e);
                }
            }

            // ── 3. Generar anotaciones RDF para cada entidad única ────────
            for (Entity entity : uniqueEntities) {

                String entityUri = MIAA + "entity_" + (++entityCounter);
                String entityClass = getEntityClass(entity.getType());
                String entityName = escapeStringLiteral(entity.getText());

                // Tipo y nombre de la entidad
                out.println("<" + entityUri + ">");
                out.println("    a <" + entityClass + "> ;");
                out.println("    <" + MIAA + "entityName> \"" + entityName
                        + "\"^^<" + XSD + "string> .");
                out.println();

                // Tripleta mentionsEntity en el documento
                out.println("<" + urlStr + ">");
                out.println("    <" + MIAA + "mentionsEntity> <" + entityUri + "> .");
                out.println();
            }

            // ── 4. Para entidades Location, consultar al LLM ──────────────
            Set<String> processedLocations = new HashSet<String>();
            for (Entity entity : uniqueEntities) {
                if ("Location".equals(entity.getType())) {
                    String locationName = entity.getText();
                    if (processedLocations.add(locationName)) {

                        System.out.println("[" + llm.toUpperCase() + "] Consultando localización: " + locationName);

                        String wikiUrl = null;
                        if ("gemini".equals(llm)) {
                            wikiUrl = queryGeminiForWikipediaUrl(locationName, urlStr, allEntityNames);
                        } else {
                            wikiUrl = queryOllamaForWikipediaUrl(locationName, urlStr, allEntityNames);
                        }

                        if (wikiUrl != null
                                && wikiUrl.startsWith("https://en.wikipedia.org/")) {

                            // mentionsInstance en el documento
                            out.println("<" + urlStr + ">");
                            out.println("    <" + MIAA + "mentionsInstance> <"
                                    + wikiUrl + "> .");
                            out.println();

                            // La URL de Wikipedia es instancia de dcterms:Location
                            out.println("<" + wikiUrl + ">");
                            out.println("    a <" + DCTERMS + "Location> .");
                            out.println();

                        } else {
                            System.out.println("[WARN] " + llm.toUpperCase() + " no devolvió URL válida para: "
                                    + locationName + " -> " + wikiUrl);
                        }
                    }
                }
            }

        } catch (Exception ex) {
            System.err.println("[ERROR] Fallo al procesar " + urlStr
                    + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONSULTA A GEMINI
    // ═══════════════════════════════════════════════════════════════════════
    /**
     * Solicita a Gemini la URL de la Wikipedia en inglés que mejor identifica
     * una localización dada.
     */
    private String queryGeminiForWikipediaUrl(String locationName,
            String sourceUrl,
            List<String> contextEntities) {
        try {
            if (geminiClient == null) {
                geminiClient = Client.builder().apiKey(GEMINI_API_KEY).build();
            }

            // Esperar para respetar cuota
            System.out.println("[GEMINI] Esperando 12 segundos para respetar los límites de la cuota...");
            Thread.sleep(12500);

            String systemInstruction = "You are a named-entity disambiguation assistant specialized in geography. "
                    + "Given a location name and contextual information about the document where "
                    + "it appears, you MUST respond with ONLY the single most likely URL from the "
                    + "English Wikipedia (starting exactly with https://en.wikipedia.org/wiki/) "
                    + "that identifies that location. "
                    + "Do NOT include any explanation, punctuation, markdown, or extra text. "
                    + "Output ONLY the bare URL on a single line.";

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .systemInstruction(
                            Content.fromParts(Part.fromText(systemInstruction)))
                    .build();

            StringBuilder userQuery = new StringBuilder();
            userQuery.append("Location to disambiguate: ").append(locationName).append("\n");
            userQuery.append("Source document URL: ").append(sourceUrl).append("\n");
            if (!contextEntities.isEmpty()) {
                List<String> ctx = contextEntities.size() > 20
                        ? contextEntities.subList(0, 20)
                        : contextEntities;
                userQuery.append("Other named entities found in the same document "
                        + "(use them as context to disambiguate the location):\n");
                for (String e : ctx) {
                    userQuery.append("  - ").append(e).append("\n");
                }
            }
            userQuery.append("\nRespond ONLY with the English Wikipedia URL.");

            GenerateContentResponse response = geminiClient.models
                    .generateContent(GEMINI_MODEL, userQuery.toString(), config);

            String result = response.text();
            if (result == null)
                return null;

            result = result.trim();
            result = result.replaceAll("(?s)```.*?```", "").trim();
            result = result.split("\\r?\\n")[0].trim();
            result = result.replaceAll("[`*]", "");

            System.out.println("[GEMINI] " + locationName + " -> " + result);
            return result;

        } catch (Exception ex) {
            System.err.println("[WARN] Consulta Gemini fallida para '"
                    + locationName + "': " + ex.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONSULTA A OLLAMA
    // ═══════════════════════════════════════════════════════════════════════
    /**
     * Solicita a Ollama la URL de la Wikipedia en inglés que mejor identifica
     * una localización dada.
     */
    private String queryOllamaForWikipediaUrl(String locationName,
            String sourceUrl,
            List<String> contextEntities) {
        try {
            String systemInstruction = "You are a named-entity disambiguation assistant specialized in geography. "
                    + "Given a location name and contextual information about the document where "
                    + "it appears, you MUST respond with ONLY the single most likely URL from the "
                    + "English Wikipedia (starting exactly with https://en.wikipedia.org/wiki/) "
                    + "that identifies that location. "
                    + "Do NOT include any explanation, punctuation, markdown, or extra text. "
                    + "Output ONLY the bare URL on a single line.";

            StringBuilder userQuery = new StringBuilder();
            userQuery.append("Location to disambiguate: ").append(locationName).append("\\n");
            userQuery.append("Source document URL: ").append(sourceUrl).append("\\n");
            if (!contextEntities.isEmpty()) {
                List<String> ctx = contextEntities.size() > 20
                        ? contextEntities.subList(0, 20)
                        : contextEntities;
                userQuery.append("Other named entities found in the same document "
                        + "(use them as context to disambiguate the location):\\n");
                for (String e : ctx) {
                    userQuery.append("  - ").append(e.replace("\"", "\\\"")).append("\\n");
                }
            }
            userQuery.append("\\nRespond ONLY with the English Wikipedia URL.");

            java.net.URL url = new java.net.URL(OLLAMA_URL);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            String jsonInputString = "{"
                    + "\"model\": \"" + OLLAMA_MODEL + "\","
                    + "\"prompt\": \"" + systemInstruction + "\\n\\n" + userQuery.toString() + "\","
                    + "\"stream\": false"
                    + "}";

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            StringBuilder responseBuilder = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                String responseLine = null;
                while ((responseLine = br.readLine()) != null) {
                    responseBuilder.append(responseLine.trim());
                }
            }

            String responseStr = responseBuilder.toString();
            String result = null;

            String responseKey = "\"response\":\"";
            int startIndex = responseStr.indexOf(responseKey);
            if (startIndex != -1) {
                startIndex += responseKey.length();
                int endIndex = responseStr.indexOf("\"", startIndex);
                if (endIndex != -1) {
                    result = responseStr.substring(startIndex, endIndex);
                    result = result.replace("\\n", "\n").replace("\\r", "\r");
                }
            }

            if (result == null)
                return null;

            result = result.trim();
            result = result.replaceAll("(?s)```.*?```", "").trim();
            result = result.split("\\r?\\n")[0].trim();
            result = result.replaceAll("[`*]", "");

            System.out.println("[OLLAMA] " + locationName + " -> " + result);
            return result;

        } catch (Exception ex) {
            System.err.println("[WARN] Consulta Ollama fallida para '"
                    + locationName + "': " + ex.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MÉTODOS AUXILIARES
    // ═══════════════════════════════════════════════════════════════════════
    private String getEntityClass(String annieType) {
        if ("Person".equals(annieType))
            return MIAA + "Person";
        if ("Location".equals(annieType))
            return MIAA + "Location";
        if ("Organization".equals(annieType))
            return MIAA + "Organization";
        return MIAA + "Entity";
    }

    private String escapeStringLiteral(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void writePrefixes(PrintWriter out) {
        out.println("@prefix rdf:     <" + RDF + "> .");
        out.println("@prefix rdfs:    <" + RDFS + "> .");
        out.println("@prefix xsd:     <" + XSD + "> .");
        out.println("@prefix dcterms: <" + DCTERMS + "> .");
        out.println("@prefix miaa:    <" + MIAA + "> .");
        out.println();
    }
}
