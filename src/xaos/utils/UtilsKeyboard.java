package xaos.utils;

import java.util.StringTokenizer;

import xaos.compat.input.Keyboard;

import xaos.Towns;

public final class UtilsKeyboard {

    public final static int FN_NONE = -1;
    public final static int FN_UP = 0;
    public final static int FN_DOWN = 1;
    public final static int FN_LEFT = 2;
    public final static int FN_RIGHT = 3;
    public final static int FN_SHOW_MISSION = 4;
    public final static int FN_SHOW_STOCK = 5;
    public final static int FN_SHOW_PRIORITIES = 6;
    public final static int FN_SHOW_TRADE = 7;
    public final static int FN_TOGGLE_FULLSCREEN = 8;
    public final static int FN_SPEED_DOWN = 9;
    public final static int FN_SPEED_UP = 10;
    public final static int FN_TOGGLE_GRID = 11;
    public final static int FN_TOGGLE_FLAT_MOUSE = 12;
    public final static int FN_PAUSE = 13;
    public final static int FN_LEVEL_DOWN = 14;
    public final static int FN_LEVEL_UP = 15;
    public final static int FN_NEXT_CITIZEN = 16;
    public final static int FN_PREVIOUS_CITIZEN = 17;
    public final static int FN_NEXT_SOLDIER = 18;
    public final static int FN_PREVIOUS_SOLDIER = 19;
    public final static int FN_NEXT_HERO = 20;
    public final static int FN_PREVIOUS_HERO = 21;
    public final static int FN_TOGGLE_MINIBLOCKS = 22;
    public final static int FN_BOT_1 = 23;
    public final static int FN_BOT_2 = 24;
    public final static int FN_BOT_3 = 25;
    public final static int FN_BOT_4 = 26;
    public final static int FN_BOT_5 = 27;
    public final static int FN_BOT_6 = 28;
    public final static int FN_BOT_7 = 29;
    public final static int FN_BOT_8 = 30;
    public final static int FN_BOT_9 = 31;
    public final static int FN_BOT_10 = 32;
    public final static int FN_TOGGLE_3D_MOUSE = 33;
    public final static int FN_TOGGLE_HIDE_UI = 34;
    public final static int FN_SCREENSHOT = 35;
    public final static int FN_TOGGLE_ITEM_BUILD_FACE = 36;

    private final static String FNSTRINGS[] = {
        "FN_UP", //$NON-NLS-1$
        "FN_DOWN", //$NON-NLS-1$
        "FN_LEFT", //$NON-NLS-1$
        "FN_RIGHT", //$NON-NLS-1$
        "FN_SHOW_MISSION", //$NON-NLS-1$
        "FN_SHOW_STOCK", //$NON-NLS-1$
        "FN_SHOW_PRIORITIES", //$NON-NLS-1$
        "FN_SHOW_TRADE", //$NON-NLS-1$
        "FN_TOGGLE_FULLSCREEN", //$NON-NLS-1$
        "FN_SPEED_DOWN", //$NON-NLS-1$
        "FN_SPEED_UP", //$NON-NLS-1$
        "FN_TOGGLE_GRID", //$NON-NLS-1$
        "FN_TOGGLE_FLAT_MOUSE", //$NON-NLS-1$
        "FN_PAUSE", //$NON-NLS-1$
        "FN_LEVEL_DOWN", //$NON-NLS-1$
        "FN_LEVEL_UP", //$NON-NLS-1$
        "FN_NEXT_CITIZEN", //$NON-NLS-1$
        "FN_PREVIOUS_CITIZEN", //$NON-NLS-1$
        "FN_NEXT_SOLDIER", //$NON-NLS-1$
        "FN_PREVIOUS_SOLDIER", //$NON-NLS-1$
        "FN_NEXT_HERO", //$NON-NLS-1$
        "FN_PREVIOUS_HERO", //$NON-NLS-1$
        "FN_TOGGLE_MINIBLOCKS", //$NON-NLS-1$
        "FN_BOT_1", //$NON-NLS-1$
        "FN_BOT_2", //$NON-NLS-1$
        "FN_BOT_3", //$NON-NLS-1$
        "FN_BOT_4", //$NON-NLS-1$
        "FN_BOT_5", //$NON-NLS-1$
        "FN_BOT_6", //$NON-NLS-1$
        "FN_BOT_7", //$NON-NLS-1$
        "FN_BOT_8", //$NON-NLS-1$
        "FN_BOT_9", //$NON-NLS-1$
        "FN_BOT_10", //$NON-NLS-1$
        "FN_TOGGLE_3D_MOUSE", //$NON-NLS-1$
        "FN_TOGGLE_HIDE_UI", //$NON-NLS-1$
        "FN_SCREENSHOT", //$NON-NLS-1$
        "FN_ITEM_BUILD_FACE" //$NON-NLS-1$
    };

    private final static String FNHUMANSTRINGS[] = {
        "UtilsKeyboard.0", //$NON-NLS-1$
        "UtilsKeyboard.1", //$NON-NLS-1$
        "UtilsKeyboard.2", //$NON-NLS-1$
        "UtilsKeyboard.3", //$NON-NLS-1$
        "UtilsKeyboard.4", //$NON-NLS-1$
        "UIPanel.32", //$NON-NLS-1$
        "UIPanel.14", //$NON-NLS-1$
        "UIPanel.25", //$NON-NLS-1$
        "MainMenuPanel.17", //$NON-NLS-1$
        "UIPanel.1", //$NON-NLS-1$
        "UIPanel.15", //$NON-NLS-1$
        "UIPanel.12", //$NON-NLS-1$
        "UIPanel.45", //$NON-NLS-1$
        "UIPanel.10", //$NON-NLS-1$
        "UIPanel.2", //$NON-NLS-1$
        "UIPanel.0", //$NON-NLS-1$
        "UIPanel.4", //$NON-NLS-1$
        "UIPanel.3", //$NON-NLS-1$
        "UIPanel.6", //$NON-NLS-1$
        "UIPanel.5", //$NON-NLS-1$
        "UIPanel.23", //$NON-NLS-1$
        "UIPanel.22", //$NON-NLS-1$
        "UIPanel.16", //$NON-NLS-1$
        "UtilsKeyboard.5", //$NON-NLS-1$
        "UtilsKeyboard.6", //$NON-NLS-1$
        "UtilsKeyboard.7", //$NON-NLS-1$
        "UtilsKeyboard.8", //$NON-NLS-1$
        "UtilsKeyboard.9", //$NON-NLS-1$
        "UtilsKeyboard.10", //$NON-NLS-1$
        "UtilsKeyboard.11", //$NON-NLS-1$
        "UtilsKeyboard.12", //$NON-NLS-1$
        "UtilsKeyboard.13", //$NON-NLS-1$
        "UtilsKeyboard.14", //$NON-NLS-1$
        "UtilsKeyboard.16", //$NON-NLS-1$
        "UtilsKeyboard.17", //$NON-NLS-1$
        "UtilsKeyboard.18", //$NON-NLS-1$
        "UtilsKeyboard.19" //$NON-NLS-1$
    };

    private static int[][] shortcuts = new int[FNSTRINGS.length][2];

    public static final String EMPTY_STRING = new String();

    public static void loadShortcuts() {
        for (int i = 0; i < FNSTRINGS.length; i++) {
            String sShortcuts = Towns.getPropertiesString(FNSTRINGS[i]);
            if (sShortcuts != null && sShortcuts.trim().length() > 0) {
                sShortcuts = sShortcuts.toUpperCase();
                StringTokenizer tokenizer = new StringTokenizer(sShortcuts.trim(), ","); //$NON-NLS-1$
                String key = tokenizer.nextToken().trim();
                if (key.equals(Keyboard.getKeyName(Keyboard.KEY_ESCAPE))) {
                    shortcuts[i][0] = Keyboard.KEY_NONE;
                } else {
                    shortcuts[i][0] = Keyboard.getKeyIndex(key);
                }
                if (tokenizer.hasMoreTokens()) {
                    key = tokenizer.nextToken().trim();
                    if (key.equals(Keyboard.getKeyName(Keyboard.KEY_ESCAPE))) {
                        shortcuts[i][1] = Keyboard.KEY_NONE;
                    } else {
                        shortcuts[i][1] = Keyboard.getKeyIndex(key);
                    }
                }
            } else {
                shortcuts[i][0] = Keyboard.KEY_NONE;
                shortcuts[i][1] = Keyboard.KEY_NONE;
            }
        }
    }

    public static void saveShortcuts(PropertiesWriter pw) throws Exception {
        pw.addSection("Shortcuts");
        for (int i = 0; i < FNSTRINGS.length; i++) {
            String value = "";

            if (shortcuts[i][0] != Keyboard.KEY_NONE) {
                value = Keyboard.getKeyName(shortcuts[i][0]);

                if (shortcuts[i][1] != Keyboard.KEY_NONE) {
                    value += ", "; //$NON-NLS-1$
                    value += Keyboard.getKeyName(shortcuts[i][1]);
                }
            }
            pw.setProperty(FNSTRINGS[i], value);
        }
    }

    public static String getTooltip(int iFN) {
        if (shortcuts[iFN][0] == Keyboard.KEY_NONE) {
            return EMPTY_STRING;
        }

        String sAux = getKeyLabel(shortcuts[iFN][0]);
        if (shortcuts[iFN][1] != Keyboard.KEY_NONE) {
            // The main keyboard's key first: "= or keypad +"
            String sSecond = getKeyLabel(shortcuts[iFN][1]);
            if (sAux.startsWith("keypad ") && !sSecond.startsWith("keypad ")) { //$NON-NLS-1$ //$NON-NLS-2$
                String sSwap = sAux;
                sAux = sSecond;
                sSecond = sSwap;
            }
            return " (" + Messages.getString("UtilsKeyboard.20") + sAux + " or " + sSecond + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        return " (" + Messages.getString("UtilsKeyboard.15") + sAux + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A key's name as it is printed on the keyboard, for tooltips: Towns
     * showed LWJGL's names ("ADD, EQUALS").  The shortcuts file keeps
     * LWJGL's names (saveShortcuts).
     */
    public static String getKeyLabel(int key) {
        String name = Keyboard.getKeyName(key);
        if (name == null) {
            return EMPTY_STRING;
        }
        switch (name) {
            case "ADD": return "keypad +"; //$NON-NLS-1$
            case "SUBTRACT": return "keypad -"; //$NON-NLS-1$
            case "MULTIPLY": return "keypad *"; //$NON-NLS-1$
            case "DIVIDE": return "keypad /"; //$NON-NLS-1$
            case "DECIMAL": return "keypad ."; //$NON-NLS-1$
            case "NUMPADENTER": return "keypad Enter"; //$NON-NLS-1$
            case "EQUALS": return "="; //$NON-NLS-1$
            case "MINUS": return "-"; //$NON-NLS-1$
            case "PERIOD": return "."; //$NON-NLS-1$
            case "COMMA": return ","; //$NON-NLS-1$
            case "SLASH": return "/"; //$NON-NLS-1$
            case "BACKSLASH": return "\\"; //$NON-NLS-1$
            case "SEMICOLON": return ";"; //$NON-NLS-1$
            case "APOSTROPHE": return "'"; //$NON-NLS-1$
            case "GRAVE": return "`"; //$NON-NLS-1$
            case "LBRACKET": return "["; //$NON-NLS-1$
            case "RBRACKET": return "]"; //$NON-NLS-1$
            case "ESCAPE": return "Esc"; //$NON-NLS-1$
            case "RETURN": return "Enter"; //$NON-NLS-1$
            case "BACK": return "Backspace"; //$NON-NLS-1$
            case "PRIOR": return "Page Up"; //$NON-NLS-1$
            case "NEXT": return "Page Down"; //$NON-NLS-1$
            case "LSHIFT": return "left Shift"; //$NON-NLS-1$
            case "RSHIFT": return "right Shift"; //$NON-NLS-1$
            case "LCONTROL": return "left Ctrl"; //$NON-NLS-1$
            case "RCONTROL": return "right Ctrl"; //$NON-NLS-1$
            case "LMENU": return "left Alt"; //$NON-NLS-1$
            case "RMENU": return "right Alt"; //$NON-NLS-1$
            default:
                break;
        }
        if (name.startsWith("NUMPAD")) { //$NON-NLS-1$
            return "keypad " + name.substring("NUMPAD".length()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (name.length() > 1 && !name.matches("F[0-9]+")) { //$NON-NLS-1$
            // SPACE, TAB, HOME, UP: as words
            return name.charAt(0) + name.substring(1).toLowerCase();
        }
        return name;
    }

    public static int getKey(int fn, int iIndex) {
        if (fn >= 0 && fn < shortcuts.length && iIndex >= 0 && iIndex < shortcuts[0].length) {
            return shortcuts[fn][iIndex];
        }

        return Keyboard.KEY_NONE;
    }

    public static String getFNString(int fn) {
        if (fn >= 0 && fn < FNSTRINGS.length) {
            return FNSTRINGS[fn];
        }

        return null;
    }

    public static String getFNHumanString(int fn) {
        if (fn >= 0 && fn < FNHUMANSTRINGS.length) {
            return Messages.getString(FNHUMANSTRINGS[fn]);
        }

        return null;
    }

    /**
     * Retorna la función a partir de la tecla pulsada o -1 si no se encuentra
     *
     * @param key Tecla
     * @return la función a partir de la tecla pulsada o -1 si no se encuentra
     */
    public static int getFN(int key) {
        for (int i = 0; i < shortcuts.length; i++) {
            if (shortcuts[i][0] == key || shortcuts[i][1] == key) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Changes the keybinding for a function. It checks for duplicates.
     *
     * @param iIndex 0/1
     * @param iFN Function index
     * @param iKey Key ID
     */
    public static void redefineKey(int iIndex, int iFN, int iKey) {
        if (iIndex == 1 && iKey == shortcuts[iFN][0]) {
            shortcuts[iFN][1] = Keyboard.KEY_NONE;
        } else {
            shortcuts[iFN][iIndex] = iKey;
            checkDuplicates(iIndex, iFN, iKey);
        }

        UtilsFiles.saveOptions();
    }

    /**
     * Check duplicates on the keybindings. If found them, it deletes them
     *
     * @param iIndex Index that recently changed the key (0/1)
     * @param iFN Function that recently changed the key
     * @param iKey Key to check
     */
    public static void checkDuplicates(int iIndex, int iFN, int iKey) {
        if (iKey != Keyboard.KEY_NONE) {
            for (int i = 0; i < shortcuts.length; i++) {
                for (int j = 0; j <= 1; j++) {
                    if (shortcuts[i][j] == iKey) {
                        // Duplicado?
                        if (iIndex != j || iFN != i) {
                            // Bingo, la borramos
                            shortcuts[i][j] = Keyboard.KEY_NONE;
                        }
                    }
                }
            }
        }
    }

    /**
     * Indica si el usuario está pulsando alguna tecla de la función pasadas
     *
     * @return true si el usuario está pulsando alguna tecla de la función
     * pasadas
     */
    public static boolean isFNKeyDown(int fn) {
        if (shortcuts[fn][0] != Keyboard.KEY_NONE && Keyboard.isKeyDown(shortcuts[fn][0])) {
            return true;
        }

        if (shortcuts[fn][1] != Keyboard.KEY_NONE && Keyboard.isKeyDown(shortcuts[fn][1])) {
            return true;
        }

        return false;
    }
}
