package es.uc3m.miaa.utils;

import org.jsoup.Jsoup;

public class TextClean {

    // Removes End-Of-Line characters from text
    public static String removeEOL(String text) {
        return text.replaceAll("\n", " ");
    }

    // Removes HTML tags from text
    public static String removeHTMLTags(String text) throws Exception {
	return Jsoup.parse(text).text();
    }
}
