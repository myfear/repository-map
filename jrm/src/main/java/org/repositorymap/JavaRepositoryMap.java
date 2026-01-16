package org.repositorymap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class JavaRepositoryMap {
    private static final Pattern PACKAGE_RE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_RE = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern TYPE_RE = Pattern.compile(
            "^\\s*(?:public|protected|private)?\\s*(?:abstract|final|sealed|non-sealed|static)?\\s*(class|interface|record|enum)\\s+(\\w+)",
            Pattern.MULTILINE
    );
    private static final Pattern ANNOTATION_RE = Pattern.compile("^\\s*@([\\w.]+)");
    private static final Pattern PUBLIC_METHOD_RE = Pattern.compile(
            "^\\s*public\\s+[\\w<>,\\[\\].?\\s]+\\s+(\\w+)\\s*\\(",
            Pattern.MULTILINE
    );
    private static final Pattern PUBLIC_FIELD_RE = Pattern.compile(
            "^\\s*public\\s+[\\w<>,\\[\\].?\\s]+\\s+(\\w+)\\s*(?:=|;)",
            Pattern.MULTILINE
    );

    private JavaRepositoryMap() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        Path projectRoot = Paths.get(options.getOrDefault("--project", "code-with-quarkus"));
        Path outputPath = Paths.get(options.getOrDefault("--output", "jrm-output.json"));

        List<JavaFile> javaFiles = findJavaFiles(projectRoot.resolve("src"));
        Map<String, Object> buildMetadata = loadPomMetadata(projectRoot.resolve("pom.xml"));

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("path", projectRoot.toString());
        project.put("module", buildMetadata.get("artifactId"));
        result.put("project", project);
        result.put("buildMetadata", buildMetadata);
        result.put("symbolIndex", buildSymbolIndex(javaFiles));
        result.put("dependencyShape", buildDependencyShape(projectRoot, javaFiles));
        result.put("usageFrequency", buildUsageFrequency(javaFiles));

        writeJson(outputPath, result);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                options.put(args[i], args[i + 1]);
                i++;
            }
        }
        return options;
    }

    private static List<JavaFile> findJavaFiles(Path root) {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .map(JavaRepositoryMap::parseJavaFile)
                    .collect(Collectors.toList());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static JavaFile parseJavaFile(Path path) {
        String content = readString(path);
        String packageName = null;
        Matcher packageMatcher = PACKAGE_RE.matcher(content);
        if (packageMatcher.find()) {
            packageName = packageMatcher.group(1);
        }
        List<String> imports = new ArrayList<>();
        Matcher importMatcher = IMPORT_RE.matcher(content);
        while (importMatcher.find()) {
            imports.add(importMatcher.group(1));
        }

        List<String> annotationsBuffer = new ArrayList<>();
        List<JavaType> types = new ArrayList<>();
        for (String line : content.split("\\R")) {
            Matcher annotationMatcher = ANNOTATION_RE.matcher(line);
            if (annotationMatcher.find()) {
                annotationsBuffer.add(annotationMatcher.group(1));
                continue;
            }
            Matcher typeMatcher = TYPE_RE.matcher(line);
            if (typeMatcher.find()) {
                String kind = typeMatcher.group(1);
                String name = typeMatcher.group(2);
                List<String> methods = new ArrayList<>(new TreeSet<>(findAll(PUBLIC_METHOD_RE, content)));
                List<String> fields = new ArrayList<>(new TreeSet<>(findAll(PUBLIC_FIELD_RE, content)));
                types.add(new JavaType(name, kind, List.copyOf(annotationsBuffer), methods, fields));
                annotationsBuffer.clear();
            } else if (!line.trim().isEmpty() && !line.trim().startsWith("//")) {
                annotationsBuffer.clear();
            }
        }

        return new JavaFile(path, packageName, imports, types);
    }

    private static Map<String, Object> loadPomMetadata(Path pomPath) {
        if (!Files.exists(pomPath)) {
            return new LinkedHashMap<>();
        }
        try (InputStream stream = Files.newInputStream(pomPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(stream);
            Element root = doc.getDocumentElement();

            String groupId = textContent(root, "groupId");
            if (groupId == null) {
                groupId = textContent(findFirstChild(root, "parent"), "groupId");
            }
            String artifactId = textContent(root, "artifactId");
            String version = textContent(root, "version");
            if (version == null) {
                version = textContent(findFirstChild(root, "parent"), "version");
            }

            Map<String, String> properties = new TreeMap<>();
            Element propertiesNode = findFirstChild(root, "properties");
            if (propertiesNode != null) {
                NodeList children = propertiesNode.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        properties.put(child.getLocalName(), child.getTextContent().trim());
                    }
                }
            }

            List<Map<String, String>> dependencies = new ArrayList<>();
            List<String> quarkusFeatures = new ArrayList<>();
            Element dependenciesNode = findFirstChild(root, "dependencies");
            if (dependenciesNode != null) {
                NodeList depNodes = dependenciesNode.getElementsByTagNameNS("*", "dependency");
                for (int i = 0; i < depNodes.getLength(); i++) {
                    Element dep = (Element) depNodes.item(i);
                    String depGroup = textContent(dep, "groupId");
                    String depArtifact = textContent(dep, "artifactId");
                    String depScope = Optional.ofNullable(textContent(dep, "scope")).orElse("compile");
                    Map<String, String> entry = new TreeMap<>();
                    entry.put("groupId", depGroup == null ? "" : depGroup);
                    entry.put("artifactId", depArtifact == null ? "" : depArtifact);
                    entry.put("scope", depScope);
                    dependencies.add(entry);
                    if ("io.quarkus".equals(depGroup) && depArtifact != null && depArtifact.startsWith("quarkus-")) {
                        quarkusFeatures.add(depArtifact);
                    }
                }
            }

            dependencies.sort(Comparator.comparing((Map<String, String> dep) -> dep.get("groupId"))
                    .thenComparing(dep -> dep.get("artifactId"))
                    .thenComparing(dep -> dep.get("scope")));
            Collections.sort(quarkusFeatures);

            List<Map<String, String>> plugins = new ArrayList<>();
            Element buildNode = findFirstChild(root, "build");
            if (buildNode != null) {
                Element pluginsNode = findFirstChild(buildNode, "plugins");
                if (pluginsNode != null) {
                    NodeList pluginNodes = pluginsNode.getElementsByTagNameNS("*", "plugin");
                    for (int i = 0; i < pluginNodes.getLength(); i++) {
                        Element plugin = (Element) pluginNodes.item(i);
                        Map<String, String> entry = new TreeMap<>();
                        entry.put("groupId", Optional.ofNullable(textContent(plugin, "groupId")).orElse(""));
                        entry.put("artifactId", Optional.ofNullable(textContent(plugin, "artifactId")).orElse(""));
                        entry.put("version", Optional.ofNullable(textContent(plugin, "version")).orElse(""));
                        entry.put("extensions", Optional.ofNullable(textContent(plugin, "extensions")).orElse(""));
                        plugins.add(entry);
                    }
                }
            }
            plugins.sort(Comparator.comparing((Map<String, String> plugin) -> plugin.get("groupId"))
                    .thenComparing(plugin -> plugin.get("artifactId"))
                    .thenComparing(plugin -> plugin.get("version")));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("groupId", groupId);
            metadata.put("artifactId", artifactId);
            metadata.put("version", version);
            metadata.put("properties", properties);
            metadata.put("dependencies", dependencies);
            metadata.put("plugins", plugins);
            metadata.put("quarkusFeatures", quarkusFeatures);
            return metadata;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to parse pom.xml", ex);
        }
    }

    private static Map<String, Object> buildDependencyShape(Path projectRoot, List<JavaFile> javaFiles) {
        Path targetClasses = projectRoot.resolve("target").resolve("classes");
        String jdepsPath = resolveExecutable("jdeps");
        if (jdepsPath != null && Files.exists(targetClasses)) {
            List<String> output = runCommand(List.of(
                    jdepsPath,
                    "-q",
                    "--recursive",
                    "--multi-release",
                    "21",
                    targetClasses.toString()
            ), projectRoot);
            Map<String, Set<String>> edges = new TreeMap<>();
            for (String line : output) {
                if (!line.contains("->")) {
                    continue;
                }
                String[] parts = line.split("->", 2);
                String left = parts[0].trim();
                String right = parts[1].trim();
                if (left.isEmpty() || right.isEmpty()) {
                    continue;
                }
                edges.computeIfAbsent(left, key -> new TreeSet<>()).add(right);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "jdeps");
            result.put("edges", edges.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> new ArrayList<>(entry.getValue()),
                            (left, right) -> left,
                            LinkedHashMap::new
                    )));
            return result;
        }

        Map<String, Set<String>> edges = new TreeMap<>();
        for (JavaFile javaFile : javaFiles) {
            if (javaFile.packageName == null) {
                continue;
            }
            for (String imported : javaFile.imports) {
                int lastDot = imported.lastIndexOf('.');
                if (lastDot <= 0) {
                    continue;
                }
                String importedPackage = imported.substring(0, lastDot);
                if (importedPackage.equals(javaFile.packageName)) {
                    continue;
                }
                edges.computeIfAbsent(javaFile.packageName, key -> new TreeSet<>()).add(importedPackage);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "imports");
        result.put("edges", edges.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new ArrayList<>(entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new
                )));
        return result;
    }

    private static Map<String, List<Map<String, Object>>> buildSymbolIndex(List<JavaFile> javaFiles) {
        Map<String, List<Map<String, Object>>> index = new TreeMap<>();
        for (JavaFile javaFile : javaFiles) {
            String packageName = javaFile.packageName == null ? "<default>" : javaFile.packageName;
            for (JavaType javaType : javaFile.types) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", javaType.name);
                entry.put("kind", javaType.kind);
                entry.put("annotations", new ArrayList<>(new TreeSet<>(javaType.annotations)));
                entry.put("publicMethods", new ArrayList<>(javaType.publicMethods));
                entry.put("publicFields", new ArrayList<>(javaType.publicFields));
                entry.put("definedIn", javaFile.path.toString());
                index.computeIfAbsent(packageName, key -> new ArrayList<>()).add(entry);
            }
        }
        for (Map.Entry<String, List<Map<String, Object>>> entry : index.entrySet()) {
            entry.getValue().sort(Comparator.comparing(e -> e.get("name").toString()));
        }
        return index;
    }

    private static List<Map<String, Object>> buildUsageFrequency(List<JavaFile> javaFiles) {
        Set<String> typeNames = javaFiles.stream()
                .flatMap(file -> file.types.stream().map(type -> type.name))
                .collect(Collectors.toCollection(TreeSet::new));
        if (typeNames.isEmpty()) {
            return List.of();
        }

        Map<Path, String> contentByFile = javaFiles.stream()
                .collect(Collectors.toMap(file -> file.path, file -> readString(file.path)));

        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : typeNames) {
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
            int total = 0;
            for (String content : contentByFile.values()) {
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    total++;
                }
            }
            int references = Math.max(total - 1, 0);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("symbol", name);
            entry.put("references", references);
            result.add(entry);
        }
        result.sort(Comparator.comparing((Map<String, Object> item) -> (Integer) item.get("references"))
                .reversed()
                .thenComparing(item -> item.get("symbol").toString()));
        return result;
    }

    private static String resolveExecutable(String binary) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(System.getProperty("path.separator"))) {
            Path candidate = Paths.get(dir, binary);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static List<String> runCommand(List<String> command, Path cwd) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            List<String> output;
            try (InputStream stream = process.getInputStream()) {
                output = readLines(stream);
            }
            int exit = process.waitFor();
            if (exit != 0) {
                return List.of();
            }
            return output;
        } catch (IOException ex) {
            return List.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static List<String> readLines(InputStream stream) throws IOException {
        return List.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static List<String> findAll(Pattern pattern, String content) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            matches.add(matcher.group(1));
        }
        return matches;
    }

    private static Element findFirstChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return (Element) nodes.item(0);
    }

    private static String textContent(Element parent, String localName) {
        Element node = findFirstChild(parent, localName);
        if (node == null) {
            return null;
        }
        String text = node.getTextContent();
        return text == null ? null : text.trim();
    }

    private static void writeJson(Path outputPath, Object payload) {
        String json = JsonWriter.write(payload);
        try {
            Files.writeString(outputPath, json, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private record JavaType(
            String name,
            String kind,
            List<String> annotations,
            List<String> publicMethods,
            List<String> publicFields
    ) {
    }

    private record JavaFile(
            Path path,
            String packageName,
            List<String> imports,
            List<JavaType> types
    ) {
    }

    private static final class JsonWriter {
        private JsonWriter() {
        }

        static String write(Object value) {
            StringBuilder builder = new StringBuilder();
            writeValue(builder, value);
            builder.append(System.lineSeparator());
            return builder.toString();
        }

        private static void writeValue(StringBuilder builder, Object value) {
            if (value == null) {
                builder.append("null");
            } else if (value instanceof String string) {
                builder.append('"').append(escape(string)).append('"');
            } else if (value instanceof Number || value instanceof Boolean) {
                builder.append(value.toString());
            } else if (value instanceof Map<?, ?> map) {
                writeMap(builder, map);
            } else if (value instanceof List<?> list) {
                writeList(builder, list);
            } else {
                builder.append('"').append(escape(value.toString())).append('"');
            }
        }

        private static void writeMap(StringBuilder builder, Map<?, ?> map) {
            builder.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(",");
                }
                first = false;
                builder.append('"').append(escape(Objects.toString(entry.getKey()))).append("\":");
                writeValue(builder, entry.getValue());
            }
            builder.append("}");
        }

        private static void writeList(StringBuilder builder, List<?> list) {
            builder.append("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    builder.append(",");
                }
                first = false;
                writeValue(builder, item);
            }
            builder.append("]");
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }
}
