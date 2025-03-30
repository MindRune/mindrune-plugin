package com.MindRune.util;

/**
 * Utility class for text processing
 */
public class TextUtil {

    /**
     * Strip color tags from text
     *
     * @param input Text to process
     * @return Text with color tags removed
     */
    public static String stripColorTags(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("<col=[0-9a-fA-F]+>", "").trim();
    }

    /**
     * Remove all HTML tags from text
     *
     * @param input Text to process
     * @return Text with HTML tags removed
     */
    public static String stripHtmlTags(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("<[^>]*>", "").trim();
    }
}