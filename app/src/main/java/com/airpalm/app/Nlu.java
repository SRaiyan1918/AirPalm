package com.airpalm.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Natural-language understanding module: sentence in, intent + slots (JSON) out.
 *
 * Today the rule based engine does the work. {@link TfliteSlot} is the plug-in point for a trained
 * intent classifier + slot filler: drop "nlu_model.tflite" and "nlu_tokenizer.json" next to the wake word
 * models and implement {@link TfliteSlot#parse}; until then it hands over to the rules.
 *
 * Example result for "WhatsApp par Rahul ko message karo main nikal chuka hoon":
 *   {"intent":"SEND_MESSAGE","app":"WhatsApp","recipient":"Rahul","message_body":"main nikal chuka hoon"}
 * (SEND_MESSAGE arrives with Phase 2.)
 */
public final class Nlu {
    private Nlu() {
    }

    public interface Engine {
        Result parse(String heard);
    }

    public static final class Result {
        public final String intent;
        public final Map<String, String> slots;
        public final VoiceCommandParser.Command command;

        Result(String intent, Map<String, String> slots, VoiceCommandParser.Command command) {
            this.intent = intent;
            this.slots = slots;
            this.command = command;
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"intent\":\"").append(esc(intent)).append("\"");
            for (Map.Entry<String, String> e : slots.entrySet()) {
                sb.append(",\"").append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append("\"");
            }
            return sb.append("}").toString();
        }

        private static String esc(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
        }
    }

    /** Rule based engine (always available). */
    public static final class Rules implements Engine {
        @Override
        public Result parse(String heard) {
            VoiceCommandParser.Command c = VoiceCommandParser.parse(heard);
            Map<String, String> slots = new LinkedHashMap<>();
            switch (c.type) {
                case VoiceCommandParser.OPEN_APP:
                    slots.put("app", c.arg);
                    break;
                case VoiceCommandParser.TAP_TEXT:
                    slots.put("target", c.arg);
                    break;
                case VoiceCommandParser.NUMBER:
                    slots.put("number", c.arg);
                    break;
                case VoiceCommandParser.TYPE_TEXT:
                    slots.put("text", c.arg);
                    break;
                case VoiceCommandParser.REPLACE:
                    slots.put("from", c.arg);
                    slots.put("to", c.arg2);
                    break;
                case VoiceCommandParser.SEARCH:
                    slots.put("query", c.arg);
                    break;
                case VoiceCommandParser.VOLUME_SET:
                    slots.put("percent", c.arg);
                    break;
                case VoiceCommandParser.FLASH_ON:
                    slots.put("state", "ON");
                    break;
                case VoiceCommandParser.FLASH_OFF:
                    slots.put("state", "OFF");
                    break;
                case VoiceCommandParser.FLASH_TOGGLE:
                    slots.put("state", "TOGGLE");
                    break;
                default:
                    break;
            }
            return new Result(intentName(c.type), slots, c);
        }
    }

    /** Slot for a future TensorFlow Lite NLU model. Falls back to the rules when no model is present. */
    public static final class TfliteSlot implements Engine {
        private final Engine fallback = new Rules();

        @Override
        public Result parse(String heard) {
            // TODO(model): run nlu_model.tflite on the tokenised sentence and map its output to intent + slots.
            return fallback.parse(heard);
        }
    }

    public static String intentName(int type) {
        switch (type) {
            case VoiceCommandParser.OPEN_APP: return "OPEN_APP";
            case VoiceCommandParser.BACK: return "BACK";
            case VoiceCommandParser.HOME: return "HOME";
            case VoiceCommandParser.RECENTS: return "RECENTS";
            case VoiceCommandParser.NOTIFICATIONS: return "NOTIFICATIONS";
            case VoiceCommandParser.QUICK_SETTINGS: return "QUICK_SETTINGS";
            case VoiceCommandParser.SCREENSHOT: return "SCREENSHOT";
            case VoiceCommandParser.LOCK: return "LOCK_SCREEN";
            case VoiceCommandParser.SCROLL_DOWN: return "SWIPE_UP";
            case VoiceCommandParser.SCROLL_UP: return "SWIPE_DOWN";
            case VoiceCommandParser.SWIPE_LEFT: return "SWIPE_LEFT";
            case VoiceCommandParser.SWIPE_RIGHT: return "SWIPE_RIGHT";
            case VoiceCommandParser.VOLUME_UP: return "VOLUME_UP";
            case VoiceCommandParser.VOLUME_DOWN: return "VOLUME_DOWN";
            case VoiceCommandParser.VOLUME_SET: return "SET_VOLUME";
            case VoiceCommandParser.MUTE: return "MUTE";
            case VoiceCommandParser.UNMUTE: return "UNMUTE";
            case VoiceCommandParser.SEARCH: return "WEB_SEARCH";
            case VoiceCommandParser.VOICE_OFF: return "STOP_LISTENING";
            case VoiceCommandParser.TAP_TEXT: return "TAP_TEXT";
            case VoiceCommandParser.SHOW_NUMBERS: return "SHOW_NUMBERS";
            case VoiceCommandParser.HIDE_NUMBERS: return "HIDE_NUMBERS";
            case VoiceCommandParser.NUMBER: return "TAP_NUMBER";
            case VoiceCommandParser.TYPE_TEXT: return "TYPE_TEXT";
            case VoiceCommandParser.REPLACE: return "REPLACE_TEXT";
            case VoiceCommandParser.UNDO: return "UNDO_TEXT";
            case VoiceCommandParser.REDO: return "REDO_TEXT";
            case VoiceCommandParser.SEND: return "SEND";
            case VoiceCommandParser.ENTER: return "ENTER";
            case VoiceCommandParser.CLEAR_TEXT: return "CLEAR_TEXT";
            case VoiceCommandParser.MEDIA_PAUSE: return "MEDIA_PAUSE";
            case VoiceCommandParser.MEDIA_PLAY: return "MEDIA_PLAY";
            case VoiceCommandParser.HELP: return "HELP";
            case VoiceCommandParser.SCREEN_ON: return "SCREEN_ON";
            case VoiceCommandParser.SCREEN_OFF: return "SCREEN_OFF";
            case VoiceCommandParser.ANSWER_CALL: return "ANSWER_CALL";
            case VoiceCommandParser.REJECT_CALL: return "REJECT_CALL";
            case VoiceCommandParser.FLASH_ON:
            case VoiceCommandParser.FLASH_OFF:
            case VoiceCommandParser.FLASH_TOGGLE: return "FLASHLIGHT";
            case VoiceCommandParser.TIME: return "GET_TIME";
            case VoiceCommandParser.DATE: return "GET_DATE";
            case VoiceCommandParser.BATTERY: return "GET_BATTERY";
            default: return "UNKNOWN";
        }
    }
}
