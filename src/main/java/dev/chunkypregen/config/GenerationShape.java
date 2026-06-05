package dev.chunkypregen.config;

public enum GenerationShape {
    CIRCLE("circle"),
    SQUARE("square");

    public final String chunkyCmdName;

    GenerationShape(String chunkyCmdName) {
        this.chunkyCmdName = chunkyCmdName;
    }
}
