package xaos.datacenter;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.text.MessageFormat;
import java.util.ArrayList;

import xaos.data.EventData;
import xaos.events.EventManager;
import xaos.events.EventManagerItem;
import xaos.main.Game;
import xaos.main.World;
import xaos.panels.MessagesPanel;
import xaos.tiles.Cell;
import xaos.tiles.entities.items.Item;
import xaos.tiles.entities.items.ItemManager;
import xaos.tiles.entities.items.ItemManagerItem;
import xaos.utils.ColorGL;
import xaos.utils.Messages;
import xaos.utils.Point3DShort;
import xaos.utils.Utils;

/**
 * Rain (the stakes rework of 2026-10-30, Patrick: "maybe on day 5 it
 * rains?"): the reason to put the hall in a building with a roof.
 *
 * From day 5 about one dawn in three the forecast says rain that day, from
 * an hour 2 to 18 after dawn for 3 to 8 hours (the log says so at dawn, the
 * orange row counts it down).  It is a Towns event (events.xml "rain": the
 * gray light and the icon), and MainPanel draws it falling.  Every hour of
 * rain, each rack and network switch with no cover over it (isUnderRoof)
 * shorts out one time in five: it is swapped for its shorted self, down
 * until a Repair with a box of spare parts, like protest damage.  Cover is
 * a roof, a wall or a floor anywhere above it, or solid ground (a hall dug
 * into a hill).
 *
 * The coming rain is saved with the game (Game.SAVEGAME_REWORK); a rain
 * under way is the world's event list, which Towns saves.
 */
public final class Weather {

    private static final String RAIN_EVENT = "rain"; //$NON-NLS-1$
    private static final int RAIN_FROM_DAY = 5;
    private static final int RAIN_PCT = 35;
    private static final int SHORT_PCT = 20;

    // The rain of the day: the turn of the day it starts (-1 when none is
    // coming) and its length in turns
    private static int rainStart = -1;
    private static int rainLength;

    // Racks and switches with no cover, counted every hour of rain for the
    // status row
    private static int exposed;

    private Weather() {
    }

    static void reset() {
        rainStart = -1;
        rainLength = 0;
        exposed = 0;
    }

    static void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(rainStart);
        out.writeInt(rainLength);
    }

    static void readExternal(ObjectInput in) throws IOException {
        rainStart = in.readInt();
        rainLength = in.readInt();
    }

    /**
     * Dawn, from day 5: about one day in three rain is forecast for that day
     * (not in a tutorial)
     */
    static void newDay(boolean bTutorial) {
        if (bTutorial || rainStart >= 0 || isRaining() || Game.getWorld().getDate().getDaysSinceStart() < RAIN_FROM_DAY) {
            return;
        }
        if (Utils.getRandomBetween(1, 100) > RAIN_PCT) {
            return;
        }
        rainStart = Utils.getRandomBetween(2, 18) * World.TIME_MODIFIER_HOUR;
        rainLength = Utils.getRandomBetween(3, 8) * World.TIME_MODIFIER_HOUR;
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " rain at " + rainStart / World.TIME_MODIFIER_HOUR + " h for " + rainLength / World.TIME_MODIFIER_HOUR + " h"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString("DataCenter.rainForecast"), rainStart / World.TIME_MODIFIER_HOUR), ColorGL.ORANGE); //$NON-NLS-1$
    }

    /**
     * Every turn: the rain starts at its hour, and every hour of it the gear
     * with no roof may short out
     */
    static void nextTurn() {
        if (rainStart >= 0 && Game.getWorld().getTurn() >= rainStart) {
            rainStart = -1;
            startRain();
        }
        if (!isRaining() || Game.getWorld().getTurn() % World.TIME_MODIFIER_HOUR != 0) {
            return;
        }
        ArrayList<Item> alExposed = getExposed();
        exposed = alExposed.size();
        for (Item item : alExposed) {
            if (Utils.getRandomBetween(1, 100) <= SHORT_PCT) {
                shortOut(item);
            }
        }
    }

    private static void startRain() {
        EventManagerItem emi = EventManager.getItem(RAIN_EVENT);
        if (emi == null || !Game.getWorld().addEvent(emi)) {
            return;
        }
        for (EventData event : Game.getWorld().getEvents()) {
            if (RAIN_EVENT.equals(event.getEventID())) {
                event.setTurns(rainLength);
            }
        }
        Game.getWorld().checkGlobalEvents();
        exposed = getExposed().size();
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " rain, " + exposed + " exposed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, MessageFormat.format(Messages.getString(exposed > 0 ? "DataCenter.rainExposed" : "DataCenter.rainCovered"), exposed), exposed > 0 ? ColorGL.RED : ColorGL.GREEN); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @return the running or idle racks and switches, installed and not down
     *         already, that have no cover over them
     */
    private static ArrayList<Item> getExposed() {
        ArrayList<Item> al = new ArrayList<Item>();
        for (Item item : World.getItems().values()) {
            ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
            if (imi == null || imi.isDcDown() || (imi.getDcIncome() <= 0 && imi.getDcPorts() <= 0) || !DataCenter.isInstalled(item)) {
                continue;
            }
            if (!isUnderRoof(item.getX(), item.getY(), item.getZ())) {
                al.add(item);
            }
        }
        return al;
    }

    /**
     * @return true when a roof piece is in the cell.  Nobody climbs or steps
     *         a level up or down through one (AStarQueueItem, LivingEntity):
     *         Towns let a living step down a hill's edge into any cell whose
     *         upper half was open, so protesters walked off a hill into a
     *         roofed hall under its roof's edge (the proper play, day 5)
     */
    public static boolean isRoofAt(int x, int y, int z) {
        Item item = World.getCell(x, y, z).getItem();
        if (item == null) {
            return false;
        }
        ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
        return imi != null && imi.isDcRoof();
    }

    /**
     * @return true when something above the cell keeps the rain off: solid
     *         ground, or a roof, wall or floor (items.xml dcRoof, wall, base)
     */
    public static boolean isUnderRoof(int x, int y, int z) {
        for (int zUp = z - 1; zUp >= 0; zUp--) {
            Cell cell = World.getCell(x, y, zUp);
            if (!cell.isMined()) {
                return true;
            }
            Item item = cell.getItem();
            if (item != null) {
                ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
                if (imi != null && (imi.isDcRoof() || imi.isWall() || imi.isBase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void shortOut(Item item) {
        ItemManagerItem imi = ItemManager.getItem(item.getIniHeader());
        boolean bRack = imi.getDcIncome() > 0;
        Point3DShort p3d = DataCenter.replaceRack(item, bRack ? "dcrackshorted" : "dcswitchshorted"); //$NON-NLS-1$ //$NON-NLS-2$
        if (Game.isHeadless()) {
            System.out.println("[DataCenter] " + Game.getWorld().getDate() + " rain shorted " + (bRack ? "a rack" : "a switch") + (p3d == null ? "" : " at " + p3d.x + "," + p3d.y + "," + p3d.z)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        }
        MessagesPanel.addMessage(MessagesPanel.TYPE_SYSTEM, Messages.getString(bRack ? "DataCenter.rainShortedRack" : "DataCenter.rainShortedSwitch"), ColorGL.RED, p3d); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @return true while it rains (the rain event is on)
     */
    public static boolean isRaining() {
        return getRainTurnsLeft() > 0;
    }

    private static int getRainTurnsLeft() {
        if (Game.getWorld() == null) {
            return 0;
        }
        for (EventData event : Game.getWorld().getEvents()) {
            if (RAIN_EVENT.equals(event.getEventID())) {
                return Math.max(1, event.getTurns());
            }
        }
        return 0;
    }

    /**
     * @return the orange row's part: rain coming, or rain falling and how
     *         much gear it falls on; null with no rain today
     */
    static String getStatus() {
        int iLeft = getRainTurnsLeft();
        if (iLeft > 0) {
            int iHours = (iLeft + World.TIME_MODIFIER_HOUR - 1) / World.TIME_MODIFIER_HOUR;
            return exposed > 0 ? MessageFormat.format(Messages.getString("DataCenter.statusRainExposed"), iHours, exposed) : MessageFormat.format(Messages.getString("DataCenter.statusRain"), iHours); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (rainStart >= 0) {
            int iIn = Math.max(1, (rainStart - Game.getWorld().getTurn() + World.TIME_MODIFIER_HOUR - 1) / World.TIME_MODIFIER_HOUR);
            return MessageFormat.format(Messages.getString("DataCenter.statusRainComing"), iIn); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * After a load: the count of gear in the rain for the status row
     */
    static void refresh() {
        exposed = isRaining() ? getExposed().size() : 0;
    }
}
