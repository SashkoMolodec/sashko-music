package com.sashkomusic.agents.library;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LibraryCommandParser {

    private static final Pattern RATE = Pattern.compile(
            "(?:rate|оціни|оцени|постав|зірок?|зі́рок?)\\s*([1-5])|^([1-5])\\s*(?:зір|stars?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ENERGY = Pattern.compile(
            "(?:energy|енергія|енергии|енерг)\\s*([1-5])|\\be([1-5])\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FUNCTION = Pattern.compile(
            "(?:function|функція|марк|познач)\\s*(intro|tool|banger|closer|інтро|тул|банжер|клозер)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENT = Pattern.compile(
            "(?:comment|коментар|коммент|коментуй|comment[:\\-]?)\\s+(.+)",
            Pattern.CASE_INSENSITIVE);

    public LibraryCommand parse(String text) {
        if (text == null) return new LibraryCommand.Unknown("порожня команда");
        String input = text.trim();
        if (input.isEmpty()) return new LibraryCommand.Unknown("порожня команда");

        Matcher m;
        m = RATE.matcher(input);
        if (m.find()) {
            String num = m.group(1) != null ? m.group(1) : m.group(2);
            return new LibraryCommand.Rate(Integer.parseInt(num));
        }

        m = ENERGY.matcher(input);
        if (m.find()) {
            String num = m.group(1) != null ? m.group(1) : m.group(2);
            return new LibraryCommand.SetEnergy("E" + num);
        }

        m = FUNCTION.matcher(input);
        if (m.find()) {
            return new LibraryCommand.SetFunction(normaliseFunction(m.group(1)));
        }

        m = COMMENT.matcher(input);
        if (m.find()) {
            return new LibraryCommand.AddComment(m.group(1).trim());
        }

        return new LibraryCommand.Unknown("не зрозумів команду: " + input);
    }

    private String normaliseFunction(String raw) {
        return switch (raw.toLowerCase()) {
            case "інтро" -> "intro";
            case "тул" -> "tool";
            case "банжер" -> "banger";
            case "клозер" -> "closer";
            default -> raw.toLowerCase();
        };
    }
}
