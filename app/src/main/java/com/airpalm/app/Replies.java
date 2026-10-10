package com.airpalm.app;

/**
 * Short spoken answers. Every reply has a Hindi-script version (spoken by a Hindi voice) and a
 * Roman Hinglish version (used if the phone has no Hindi voice). Pure Java so it can be tested on a PC.
 */
public final class Replies {
    private Replies() {
    }

    public static final class Reply {
        public final String hi;
        public final String roman;

        Reply(String hi, String roman) {
            this.hi = hi;
            this.roman = roman;
        }
    }

    private static Reply r(String hi, String roman) {
        return new Reply(hi, roman);
    }

    public static Reply done() {
        return r("हो गया, Boss", "Ho gaya, Boss");
    }

    public static Reply failed() {
        return r("Boss, ये नहीं हो पाया", "Boss, ye nahi ho paya");
    }

    public static Reply notUnderstood() {
        return r("Boss, समझ नहीं आया", "Boss, samajh nahi aaya");
    }

    public static Reply flashOn() {
        return r("फ्लैशलाइट चालू कर दी है, Boss", "Flashlight on kar di hai, Boss");
    }

    public static Reply flashOff() {
        return r("फ्लैशलाइट बंद कर दी है, Boss", "Flashlight band kar di hai, Boss");
    }

    public static Reply openApp(String label) {
        return r(label + " खोल रहा हूँ, Boss", label + " khol raha hun, Boss");
    }

    public static Reply appNotFound(String name) {
        return r("Boss, " + name + " नाम का ऐप नहीं मिला", "Boss, " + name + " naam ka app nahi mila");
    }

    public static Reply notOnScreen(String what) {
        return r("Boss, स्क्रीन पर " + what + " नहीं मिला", "Boss, screen par " + what + " nahi mila");
    }

    public static Reply noTextBox() {
        return r("Boss, कोई टेक्स्ट बॉक्स नहीं मिला", "Boss, koi text box nahi mila");
    }

    public static Reply typed() {
        return r("लिख दिया, Boss", "Likh diya, Boss");
    }

    public static Reply volumeUp() {
        return r("आवाज़ बढ़ा दी है, Boss", "Awaaz badha di hai, Boss");
    }

    public static Reply volumeDown() {
        return r("आवाज़ कम कर दी है, Boss", "Awaaz kam kar di hai, Boss");
    }

    public static Reply volumeSet(int pct) {
        return r("आवाज़ " + pct + " प्रतिशत कर दी है, Boss", "Awaaz " + pct + " percent kar di hai, Boss");
    }

    public static Reply muted() {
        return r("आवाज़ बंद कर दी है, Boss", "Awaaz band kar di hai, Boss");
    }

    public static Reply unmuted() {
        return r("आवाज़ चालू कर दी है, Boss", "Awaaz chalu kar di hai, Boss");
    }

    public static Reply screenOff() {
        return r("स्क्रीन बंद कर रहा हूँ, Boss", "Screen band kar raha hun, Boss");
    }

    public static Reply callAnswered() {
        return r("कॉल उठा ली है, Boss", "Call utha li hai, Boss");
    }

    public static Reply callEnded() {
        return r("कॉल काट दी है, Boss", "Call kaat di hai, Boss");
    }

    public static Reply needPermission(String what) {
        return r("Boss, पहले " + what + " की अनुमति दीजिए", "Boss, pehle " + what + " ki permission dijiye");
    }

    // ---------------------------------------------------------------- time, date, battery

    private static String period(int hour24, boolean hindi) {
        if (hour24 >= 4 && hour24 <= 11) return hindi ? "सुबह" : "subah";
        if (hour24 >= 12 && hour24 <= 15) return hindi ? "दोपहर" : "dopahar";
        if (hour24 >= 16 && hour24 <= 18) return hindi ? "शाम" : "shaam";
        return hindi ? "रात" : "raat";
    }

    public static Reply time(int hour24, int minute) {
        int h12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
        if (minute == 0) {
            return r("अभी " + period(hour24, true) + " के " + h12 + " बजे हैं, Boss",
                    "Abhi " + period(hour24, false) + " ke " + h12 + " baje hain, Boss");
        }
        return r("अभी " + period(hour24, true) + " के " + h12 + " बजकर " + minute + " मिनट हुए हैं, Boss",
                "Abhi " + period(hour24, false) + " ke " + h12 + " bajkar " + minute + " minute hue hain, Boss");
    }

    private static final String[] DAYS_HI = {"रविवार", "सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार"};
    private static final String[] DAYS_RO = {"Ravivar", "Somvar", "Mangalvar", "Budhvar", "Guruvar", "Shukravar", "Shanivar"};
    private static final String[] MONTHS_HI = {"जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई",
            "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर"};
    private static final String[] MONTHS_RO = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};

    /** @param weekday 1 = Sunday ... 7 = Saturday (java.util.Calendar.DAY_OF_WEEK) */
    public static Reply date(int day, int month1to12, int year, int weekday) {
        int w = Math.max(1, Math.min(7, weekday)) - 1;
        int m = Math.max(1, Math.min(12, month1to12)) - 1;
        return r("आज " + DAYS_HI[w] + ", " + day + " " + MONTHS_HI[m] + " " + year + " है, Boss",
                "Aaj " + DAYS_RO[w] + ", " + day + " " + MONTHS_RO[m] + " " + year + " hai, Boss");
    }

    public static Reply battery(int pct, boolean charging) {
        return r("बैटरी " + pct + " प्रतिशत है" + (charging ? " और चार्ज हो रही है" : "") + ", Boss",
                "Battery " + pct + " percent hai" + (charging ? " aur charge ho rahi hai" : "") + ", Boss");
    }
}
