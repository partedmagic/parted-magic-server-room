package xaos.datacenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import xaos.main.Game;
import xaos.utils.Log;
import xaos.utils.Messages;

/**
 * The player's best buyouts (Buyout): a text file in the user folder, one line a
 * sale, biggest first, kept across games.  Not saved with any game, so it
 * outlives the saves.  Each line: price|days|site name|site type.
 */
public final class BuyoutList {

    private static final String FILE_NAME = "buyouts.txt"; //$NON-NLS-1$
    private static final String SEPARATOR = "|"; //$NON-NLS-1$
    private static final int MAX_KEPT = 100;

    private BuyoutList() {
    }

    private static Path getPath() {
        return Path.of(Game.getUserFolder(), FILE_NAME);
    }

    /** One sale, read back from the file */
    private static final class Entry implements Comparable<Entry> {
        final int price;
        final int days;
        final String site;
        final String type;

        Entry(int iPrice, int iDays, String sSite, String sType) {
            price = iPrice;
            days = iDays;
            site = sSite;
            type = sType;
        }

        // Biggest first; the same price goes to whoever got there sooner
        @Override
        public int compareTo(Entry other) {
            return price != other.price ? Integer.compare(other.price, price) : Integer.compare(days, other.days);
        }

        String toLine() {
            return price + SEPARATOR + days + SEPARATOR + site + SEPARATOR + type;
        }
    }

    private static ArrayList<Entry> read() {
        ArrayList<Entry> alEntries = new ArrayList<Entry>();
        Path path = getPath();
        if (!Files.exists(path)) {
            return alEntries;
        }
        try {
            List<String> alLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String sLine : alLines) {
                String[] as = sLine.split("\\|", 4); //$NON-NLS-1$
                if (as.length == 4) {
                    try {
                        alEntries.add(new Entry(Integer.parseInt(as[0]), Integer.parseInt(as[1]), as[2], as[3]));
                    } catch (NumberFormatException e) {
                        // Someone edited the file: skip the line
                    }
                }
            }
        } catch (IOException e) {
            Log.log(Log.LEVEL_ERROR, "Could not read the best buyouts: " + e, "BuyoutList"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Collections.sort(alEntries);
        return alEntries;
    }

    /**
     * Adds a sale to the file
     *
     * @return its place on the list, 1 for the biggest
     */
    static int add(int iPrice, int iDays, String sSite, String sType) {
        Entry entry = new Entry(iPrice, iDays, clean(sSite), clean(sType));
        ArrayList<Entry> alEntries = read();
        alEntries.add(entry);
        Collections.sort(alEntries);
        int iRank = alEntries.indexOf(entry) + 1;
        ArrayList<String> alLines = new ArrayList<String>();
        for (int i = 0; i < alEntries.size() && i < MAX_KEPT; i++) {
            alLines.add(alEntries.get(i).toLine());
        }
        try {
            Files.write(getPath(), alLines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.log(Log.LEVEL_ERROR, "Could not save the best buyouts: " + e, "BuyoutList"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return iRank;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(SEPARATOR, " ").replace('\n', ' ').replace('\r', ' '); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @return the main menu's lines for the biggest sales, or an empty list
     */
    public static ArrayList<String> getMenuLines(int iMax) {
        ArrayList<String> alLines = new ArrayList<String>();
        ArrayList<Entry> alEntries = read();
        for (int i = 0; i < alEntries.size() && i < iMax; i++) {
            Entry entry = alEntries.get(i);
            alLines.add(MessageFormat.format(Messages.getString("MainMenuPanel.bestLine"), i + 1, entry.price, entry.site, entry.type, entry.days)); //$NON-NLS-1$
        }
        return alLines;
    }
}
