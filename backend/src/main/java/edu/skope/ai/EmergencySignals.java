package edu.skope.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative warning cues, not an emergency diagnosis. */
public final class EmergencySignals {
    private static final Pattern HAZARD = Pattern.compile("타는냄새|감전|불꽃|화재|가스냄새|가스누출|"
            + "연기(?=$|[,;.!?\\r\\n]|가|는|도|를|와|과|나|이나|및|없|발생|보입|보여)|불이났|불났|스파크");
    private static final Pattern BOUNDARY = Pattern.compile("[.!?。;,\\n\\r]");
    private static final Pattern COORDINATED = Pattern.compile("(?:은|는|이|가|도)?"
            + "(?:와|과|및|이나|나|또는|혹은|그리고|,(?:그리고|및|또는|혹은)?)(?:같은)?");
    private static final Pattern NEGATED = Pattern.compile(
            "^(?:은|는|이|가|도|을|를)?(?:(?:전혀|아예|조금도|현재는?|지금은?|이제는?|더는|더이상|아직은?)){0,4}"
            + "(?:없(?:다|습니다|어요|음|고|으며|지만|는데|으나|었|어|는)|"
            + "아니(?:다|에요|예요|고|며|지만|라|었습니다)|아닙니다|"
            + "안(?:납니다|나요|나고|나며|났|보입|보여|느껴)|"
            + "(?:나|발생하|발생되|보이|느껴지|새|일어나)(?:지|지는)않|"
            + "(?:현상|증상|문제|위험)(?:은|는|이|가)?없)");
    private static final Pattern UNCERTAIN_NEGATION = Pattern.compile(
            "없지않|없지는않|아니지않|아니지는않|않지않|않지는않|"
            + "없는지|없을지|아닌지|않는지|없어보|"
            + "(?:없|아니|아닌|않).{0,12}(?:것같|듯|모르|불확실|확실하지|단정|확인못|보장|추정)|"
            + "(?:없|아니|않).{0,8}(?:다고|다는|것은|것이).{0,8}(?:아니|아닌|아닙|않)");

    private EmergencySignals() { }

    public static boolean detected(String text) {
        if (text == null || text.isBlank()) return false;
        // Keep sentence boundaries so a denial in another sentence cannot suppress a warning.
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^\\S\\r\\n]+", "");
        List<int[]> hazards = new ArrayList<>();
        Matcher matcher = HAZARD.matcher(normalized);
        while (matcher.find()) hazards.add(new int[]{matcher.start(), matcher.end()});
        for (int index = 0; index < hazards.size(); index++) {
            int end = hazards.get(index)[1];
            int next = index + 1;
            // A list shares its predicate; "연기가 나고, 불꽃은 없습니다" is not a list.
            while (next < hazards.size()
                    && COORDINATED.matcher(normalized.substring(end, hazards.get(next)[0])).matches()) {
                end = hazards.get(next)[1];
                next++;
            }
            int limit = next < hazards.size() ? hazards.get(next)[0] : normalized.length();
            String suffix = normalized.substring(end, limit);
            Matcher boundary = BOUNDARY.matcher(suffix);
            if (boundary.find()) suffix = suffix.substring(0, boundary.start());
            if (!NEGATED.matcher(suffix).find() || UNCERTAIN_NEGATION.matcher(suffix).find()) return true;
        }
        return false;
    }
}
