package io.conflictradar.ingestion.config;

public record RssSource(
        String url,
        String name,
        double weight,
        boolean enabled
) {
    public String getSimpleName() {
        if (url.contains("bbc")) return "BBC";
        if (url.contains("reuters")) return "Reuters";
        if (url.contains("cnn")) return "CNN";
        return name;
    }
}
