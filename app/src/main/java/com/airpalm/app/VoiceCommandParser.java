package com.airpalm.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what the speech recogniser heard into one of a few simple commands.
 * Pure Java (no Android classes) so it can be tested on a PC.
 *
 * Understands English and Hinglish ("youtube kholo", "open chrome", "peeche", "neeche scroll"),
 * and Hindi (Devanagari) for the common words and app names.
 */
public class VoiceCommandParser {

    public static final int UNKNOWN = 0;
    public static final int OPEN_APP = 1;       // arg = spoken app name
    public static final int BACK = 2;
    public static final int HOME = 3;
    public static final int RECENTS = 4;
    public static final int NOTIFICATIONS = 5;
    public static final int QUICK_SETTINGS = 6;
    public static final int SCREENSHOT = 7;
    public static final int LOCK = 8;
    public static final int SCROLL_DOWN = 9;    // page moves down (finger swipes up)
    public static final int SCROLL_UP = 10;     // page moves up (finger swipes down)
    public static final int SWIPE_LEFT = 11;
    public static final int SWIPE_RIGHT = 12;
    public static final int VOLUME_UP = 13;
    public static final int VOLUME_DOWN = 14;
    public static final int SEARCH = 15;        // arg = query
    public static final int VOICE_OFF = 16;
    public static final int TAP_TEXT = 17;     // arg = text to find on screen and tap
    public static final int TYPE_TEXT = 18;    // arg = text to type into the active text box (original casing)
    public static final int SEND = 19;
    public static final int ENTER = 20;
    public static final int CLEAR_TEXT = 21;
    public static final int MEDIA_PAUSE = 22;
    public static final int MEDIA_PLAY = 23;
    public static final int HELP = 24;

    public static class Command {
        public final int type;
        public final String arg;
        /** true when no "open"-style word was spoken (just a name), so be careful with it */
        public final boolean bare;

        Command(int type, String arg, boolean bare) {
            this.type = type;
            this.arg = arg;
            this.bare = bare;
        }

        @Override
        public String toString() {
            return type + (arg.isEmpty() ? "" : ":" + arg) + (bare ? "(bare)" : "");
        }
    }

    // words that only say "do it" and carry no meaning
    private static final Set<String> TRIGGERS = new HashSet<>(Arrays.asList(
            "open", "launch", "start", "run", "kholo", "khol", "kholna", "kholiye", "chalao", "chalu", "chala",
            "karo", "kar", "do", "de", "dena", "please", "plz", "pls", "go", "to", "the", "show", "jao", "ji",
            "lo", "le", "lagao", "laga", "mere", "mera", "meri", "ek", "my", "ko", "take"));
    // subset of TRIGGERS that really means "open an app"
    private static final Set<String> OPEN_WORDS = new HashSet<>(Arrays.asList(
            "open", "launch", "start", "run", "kholo", "khol", "kholna", "kholiye", "chalao", "chalu", "chala"));

    private static final Set<String> TAP_WORDS = new HashSet<>(Arrays.asList(
            "tap", "click", "press", "select", "touch", "dabao", "dabana", "daba"));
    private static final Set<String> TAP_FILLERS = new HashSet<>(Arrays.asList(
            "par", "pe", "pr", "on", "at", "button", "icon", "option", "wala", "wale", "the"));
    private static final Pattern TYPE_RE = Pattern.compile(
            "^\\s*(likho|likh|type|write|लिखो|लिखें|लिख)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    private static final Map<String, String[]> SYSTEM = new HashMap<>();
    private static final Map<String, String> DEVANAGARI = new HashMap<>();
    private static final Map<String, String> APP_ALIASES = new HashMap<>();

    private static void sys(int type, String... phrases) {
        for (String p : phrases) SYSTEM.put(sortedKey(p), new String[]{String.valueOf(type)});
    }

    static {
        sys(BACK, "back", "peeche", "piche", "pichhe", "wapas", "vapas", "go back", "back jao");
        sys(HOME, "home", "home screen", "homescreen", "ghar", "main screen");
        sys(RECENTS, "recents", "recent", "recent apps", "recents apps", "overview", "multitask", "multitasking");
        sys(NOTIFICATIONS, "notifications", "notification", "notif", "notifs");
        sys(QUICK_SETTINGS, "quick settings", "quicksettings", "control center", "quick panel");
        sys(SCREENSHOT, "screenshot", "screen shot", "screenshot lo", "capture screen");
        sys(LOCK, "lock", "lock screen", "lock phone", "screen lock", "phone lock", "lockscreen");
        sys(SCROLL_DOWN, "scroll down", "down", "neeche", "niche", "neeche scroll", "scroll neeche", "swipe up");
        sys(SCROLL_UP, "scroll up", "up", "upar", "ooper", "oopar", "upar scroll", "scroll upar", "swipe down");
        sys(SWIPE_LEFT, "swipe left", "left", "baayen", "bayen", "left swipe");
        sys(SWIPE_RIGHT, "swipe right", "right", "daayen", "dayen", "right swipe");
        sys(VOLUME_UP, "volume up", "volume badhao", "volume badha", "awaaz badhao", "awaz badhao", "louder",
                "volume increase", "increase volume", "volume zyada", "awaaz zyada");
        sys(VOLUME_DOWN, "volume down", "volume kam", "awaaz kam", "awaz kam", "volume ghatao", "quieter",
                "volume decrease", "decrease volume", "awaaz ghatao");
        sys(VOICE_OFF, "stop listening", "voice off", "voice band", "sunna band", "mic off", "stop voice",
                "listening off", "voice stop");

        sys(SEND, "send", "bhejo", "bhej", "send message", "message send");
        sys(ENTER, "enter");
        sys(CLEAR_TEXT, "clear", "clear text", "text clear", "saaf", "mita", "mitao", "delete text");
        sys(MEDIA_PAUSE, "pause", "ruko", "roko", "rukiye", "pause video", "video pause", "stop video",
                "video stop", "video ruko", "video roko");
        sys(MEDIA_PLAY, "play", "resume", "continue", "play video", "video play", "resume video",
                "video resume", "jaari");
        sys(HELP, "help", "commands", "madad", "help me");

        String[][] dev = {
                {"टैप", "tap"}, {"क्लिक", "click"}, {"दबाओ", "dabao"}, {"दबाना", "dabana"}, {"भेजो", "bhejo"},
                {"सेंड", "send"}, {"एंटर", "enter"}, {"क्लियर", "clear"}, {"पॉज", "pause"}, {"रुको", "ruko"},
                {"रोको", "roko"}, {"रेज़्यूम", "resume"}, {"हेल्प", "help"}, {"मदद", "madad"},
                {"खोलो", "kholo"}, {"खोलें", "kholo"}, {"खोल", "khol"}, {"खोलिए", "kholo"}, {"चलाओ", "chalao"},
                {"चालू", "chalu"}, {"करो", "karo"}, {"कर", "kar"}, {"ओपन", "open"}, {"पीछे", "peeche"},
                {"वापस", "wapas"}, {"घर", "ghar"}, {"होम", "home"}, {"बैक", "back"}, {"नीचे", "neeche"},
                {"ऊपर", "upar"}, {"स्क्रॉल", "scroll"}, {"वॉल्यूम", "volume"}, {"बढ़ाओ", "badhao"},
                {"बढ़ाओ", "badhao"}, {"कम", "kam"}, {"घटाओ", "ghatao"}, {"सर्च", "search"}, {"लॉक", "lock"},
                {"स्क्रीनशॉट", "screenshot"}, {"नोटिफिकेशन", "notifications"}, {"आवाज", "awaaz"},
                {"आवाज़", "awaaz"}, {"बंद", "band"}, {"वॉइस", "voice"}, {"स्वाइप", "swipe"},
                {"लेफ्ट", "left"}, {"राइट", "right"}, {"अप", "up"}, {"डाउन", "down"},
                {"यूट्यूब", "youtube"}, {"यूट्यूब", "youtube"}, {"व्हाट्सएप", "whatsapp"}, {"व्हाट्सऐप", "whatsapp"},
                {"वाट्सएप", "whatsapp"}, {"वॉट्सऐप", "whatsapp"}, {"इंस्टाग्राम", "instagram"},
                {"क्रोम", "chrome"}, {"कैमरा", "camera"}, {"सेटिंग", "settings"}, {"सेटिंग्स", "settings"},
                {"गैलरी", "gallery"}, {"मैप्स", "maps"}, {"फोन", "phone"}, {"गूगल", "google"},
                {"फेसबुक", "facebook"}, {"टेलीग्राम", "telegram"}, {"नेटफ्लिक्स", "netflix"},
                {"स्पॉटिफाई", "spotify"}, {"जीमेल", "gmail"}, {"कैलकुलेटर", "calculator"}, {"घड़ी", "clock"},
                {"फोटो", "photos"}, {"फोटोज", "photos"}, {"मैसेज", "messages"}, {"प्ले", "play"},
                {"स्टोर", "store"}, {"ट्विटर", "twitter"}, {"स्नैपचैट", "snapchat"}
        };
        for (String[] d : dev) DEVANAGARI.put(d[0], d[1]);

        APP_ALIASES.put("insta", "instagram");
        APP_ALIASES.put("yt", "youtube");
        APP_ALIASES.put("whatsap", "whatsapp");
        APP_ALIASES.put("whatsup", "whatsapp");
        APP_ALIASES.put("watsapp", "whatsapp");
        APP_ALIASES.put("gpay", "googlepay");
        APP_ALIASES.put("fb", "facebook");
        APP_ALIASES.put("tg", "telegram");
        APP_ALIASES.put("clockapp", "clock");
        APP_ALIASES.put("contacts", "contacts");
    }

    // ------------------------------------------------------------------ parsing

    public static Command parse(String heard) {
        // "likho hello world": everything after the first word is typed exactly as heard
        if (heard != null) {
            Matcher tm = TYPE_RE.matcher(heard);
            if (tm.matches()) return new Command(TYPE_TEXT, tm.group(2).trim(), false);
        }

        List<String> words = tokens(heard);
        if (words.isEmpty()) return new Command(UNKNOWN, "", false);

        boolean openIntent = false;
        List<String> core = new ArrayList<>();
        for (String w : words) {
            if (OPEN_WORDS.contains(w)) openIntent = true;
            if (!TRIGGERS.contains(w)) core.add(w);
        }
        if (core.isEmpty()) return new Command(UNKNOWN, "", false);
        String coreStr = join(core);

        // 1) exact system phrases (order of words does not matter)
        String[] hit = SYSTEM.get(sortedKey(coreStr));
        if (hit != null) return new Command(Integer.parseInt(hit[0]), "", false);

        // 1b) tap something that is written on the screen: "tap subscribe", "subscribe dabao"
        if (core.size() >= 2) {
            List<String> tapArg = null;
            if (TAP_WORDS.contains(core.get(0))) tapArg = new ArrayList<>(core.subList(1, core.size()));
            else if (TAP_WORDS.contains(core.get(core.size() - 1))) tapArg = new ArrayList<>(core.subList(0, core.size() - 1));
            if (tapArg != null) {
                tapArg.removeAll(TAP_FILLERS);
                if (!tapArg.isEmpty()) return new Command(TAP_TEXT, join(tapArg), false);
            }
        }

        // 2) search
        if (core.size() >= 2 && (core.get(0).equals("search") || core.get(0).equals("find"))) {
            List<String> q = new ArrayList<>(core.subList(1, core.size()));
            q.remove("for");
            if (!q.isEmpty()) return new Command(SEARCH, join(q), false);
        }
        if (core.size() >= 2 && core.get(core.size() - 1).equals("search")) {
            List<String> q = new ArrayList<>(core.subList(0, core.size() - 1));
            return new Command(SEARCH, join(q), false);
        }

        // 3) app name (with or without "open")
        List<String> name = new ArrayList<>(core);
        if (name.size() > 1) {
            name.remove("app");
            name.remove("application");
        }
        if (name.isEmpty()) return new Command(UNKNOWN, "", false);
        if (openIntent) return new Command(OPEN_APP, join(name), false);
        if (name.size() <= 3) return new Command(OPEN_APP, join(name), true);
        return new Command(UNKNOWN, "", false);
    }

    // ------------------------------------------------------------------ app matching

    /**
     * Finds the installed app that best fits the spoken name.
     * @return index into labels, or -1 if nothing fits well enough
     */
    public static int matchApp(String spoken, List<String> labels) {
        String q = squash(spoken);
        if (APP_ALIASES.containsKey(q)) q = APP_ALIASES.get(q);
        if (q.length() < 2) return -1;

        int best = -1;
        int bestScore = 0;
        int bestLenDiff = Integer.MAX_VALUE;
        for (int i = 0; i < labels.size(); i++) {
            String l = squash(labels.get(i));
            if (l.length() < 2) continue;
            int score = score(q, l);
            int lenDiff = Math.abs(l.length() - q.length());
            if (score > bestScore || (score == bestScore && score > 0 && lenDiff < bestLenDiff)) {
                best = i;
                bestScore = score;
                bestLenDiff = lenDiff;
            }
        }
        return bestScore >= 70 ? best : -1;
    }

    /** How well a spoken text fits a label seen on screen (0..100, 70+ is a match). */
    public static int matchScore(String spoken, String label) {
        String q = squash(spoken);
        if (APP_ALIASES.containsKey(q)) q = APP_ALIASES.get(q);
        String l = squash(label);
        if (q.length() < 2 || l.length() < 2) return 0;
        return score(q, l);
    }

    private static int score(String q, String l) {
        if (q.equals(l)) return 100;
        if (q.length() >= 3 && l.startsWith(q)) return 90;
        if (l.length() >= 3 && q.startsWith(l)) return 85;
        if (q.length() >= 4 && l.contains(q)) return 75;
        int max = q.length() >= 7 ? 2 : (q.length() >= 4 ? 1 : 0);
        if (max > 0) {
            int d = levenshtein(q, l);
            if (d <= max) return 80 - d * 3;
        }
        return 0;
    }

    // ------------------------------------------------------------------ helpers

    static List<String> tokens(String heard) {
        List<String> out = new ArrayList<>();
        if (heard == null) return out;
        String t = heard.toLowerCase(Locale.ROOT).replace("'", "").replace("\u2019", "");
        t = t.replaceAll("[^\\p{L}\\p{N}\\p{M}]+", " ").trim();
        if (t.isEmpty()) return out;
        for (String w : t.split("\\s+")) {
            String mapped = DEVANAGARI.get(w);
            out.add(mapped != null ? mapped : w);
        }
        return out;
    }

    private static String join(List<String> w) {
        StringBuilder sb = new StringBuilder();
        for (String s : w) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    /** words sorted alphabetically, so "neeche scroll" == "scroll neeche" */
    private static String sortedKey(String phrase) {
        String[] w = phrase.toLowerCase(Locale.ROOT).trim().split("\\s+");
        Arrays.sort(w);
        StringBuilder sb = new StringBuilder();
        for (String s : w) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    /** lower case, letters+digits only ("You Tube" -> "youtube") */
    static String squash(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String w : tokens(s)) sb.append(w);
        return sb.toString().replaceAll("[^a-z0-9\\p{L}]", "");
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
