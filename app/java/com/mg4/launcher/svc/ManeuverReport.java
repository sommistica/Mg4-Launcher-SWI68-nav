package com.mg4.launcher.svc;

import java.util.Locale;

/** Allowlisted instruction types only: never pass arbitrary navigation text to logs. */
public final class ManeuverReport {
    private ManeuverReport() {}

    public static String instructionType(String text) {
        if (text == null) return "unknown";
        switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "turn left": return "turn_left";
            case "turn right": return "turn_right";
            case "direction to left": return "bear_left";
            case "direction to right": return "bear_right";
            case "keep left": return "keep_left";
            case "keep right": return "keep_right";
            case "exit left": return "exit_left";
            case "exit right": return "exit_right";
            case "u-turn": return "u_turn";
            case "reach destination": return "destination";
            case "go straight": return "straight";
            case "go straight (at a rotary)": return "roundabout_straight";
            case "to left 9 o’clock (at a rotary)": return "roundabout_left";
            case "to right 3 o’clock (at a rotary)": return "roundabout_right";
            case "to right 1 o’clock (at a rotary)": return "roundabout_one";
            case "u-turn (at a rotary)": return "roundabout_u_turn";
            default: return "unknown";
        }
    }

    public static String body(int code, int meters, String text) {
        return "===== MISSING NAVIGATION ICON =====\nIcon code: " + code
                + "\nInstruction type: " + instructionType(text)
                + "\nDistance meters: " + meters + "\n";
    }
}
