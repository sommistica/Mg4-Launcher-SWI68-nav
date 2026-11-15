package com.mg4.launcher.ui;

import com.mg4.launcher.R;

/**
 * EN: Map only observed MG maneuver codes; code 0 was confirmed by the driver.
 * Unknown codes get a neutral icon rather than a guessed driving instruction.
 * IT: Associa solo codici MG osservati; il codice 0 e stato confermato dal guidatore.
 * I codici sconosciuti ricevono un'icona neutra, senza inventare indicazioni di guida.
 */
public final class ManeuverIcons {
    private ManeuverIcons() {}

    public static int drawable(int code) {
        switch (code) {
            case 0: return R.drawable.ic_maneuver_straight;
            case 1: return R.drawable.ic_maneuver_left;
            case 2: return R.drawable.ic_maneuver_right;
            case 3: return R.drawable.ic_maneuver_bear_left;
            case 5: return R.drawable.ic_maneuver_uturn;
            case 9: return R.drawable.ic_maneuver_roundabout_straight;
            case 10: return R.drawable.ic_maneuver_roundabout_left;
            case 11: return R.drawable.ic_maneuver_roundabout_right;
            case 12: return R.drawable.ic_maneuver_roundabout_uturn;
            case 13: return R.drawable.ic_maneuver_roundabout_one;
            case 17: return R.drawable.ic_maneuver_keep_left;
            case 18: return R.drawable.ic_maneuver_keep_right;
            case 21: return R.drawable.ic_maneuver_destination;
            case 37: return R.drawable.ic_maneuver_exit_right;
            default: return R.drawable.ic_maneuver_unknown;
        }
    }
}
